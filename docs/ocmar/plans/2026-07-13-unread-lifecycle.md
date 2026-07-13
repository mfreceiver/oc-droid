# 未读机制重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use ocmar-subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将未读提示从「消息事件驱动 + busy 重标记」迁移为「root session busy→idle 生命周期驱动」，修复 ping-pong 故障，并补齐 question tree 聚合与全生命周期未读清理。

**Architecture:** 单一 `UnreadState` 内存态（去掉 `tempClearedUnread`）。未读生产唯一来源 = SSE `session.status` busy→idle 转换（root、非当前）+ REST 重连兜底（本地已知 busy、快照缺失）。未读清除 = 打开/归档/删除/关闭 tab/disconnectWorkdir/host 切换。question 机制保留，仅补 UI 层 tree 聚合。共享 session-tree 推导纯函数贯穿所有 surface。

**Tech Stack:** Kotlin, Jetpack Compose, StateFlow/MutableStateFlow (CAS `update{}`), JUnit4 + `org.junit.Assert.*`，校验 `./scripts/check.sh --full`。

**Spec:** `docs/ocmar/specs/2026-07-13-unread-lifecycle.md`

## Global Constraints

- 一次原子 commit（开发期分 task，最终提交不拆）。
- git 基线 base = `b1f66d79c41f58e28b95b51e483bd6cd4ae0f985`。
- 不 `git add/commit`，除非用户显式要求（ocmar 默认 + AGENTS.md）。每 task 末记录 `git diff --stat`。
- slice mutation 约束在 `Dispatchers.Main.immediate`（串行，无 TOCTOU）。
- 不引入持久化未读状态（接受冷重启丢失窗口）。
- 不臆造 `session.deleted` SSE 事件（仓库无此分支）。
- 不修改 `Session`/`QuestionRequest` data model 的序列化字段。
- 测试框架统一 JUnit4 + `org.junit.Assert.*`；纯函数测试放 `SessionSyncPureFunctionsTest.kt` 或同包新建。

---

## File Structure

| 文件 | 角色 | 本次改动 |
|---|---|---|
| `ui/controller/SessionTree.kt`（新建） | 共享 session-tree 推导纯函数 | Task1 新建：`allSessionsById` / `rootIdOf` / `treeIds` / `subtreeIds` |
| `ui/SessionMutationActions.kt` | 本地归档/删除 | Task1 升级 `sessionSubtreeIds`→internal 三源；Task5 补 unread/question 清理 |
| `ui/controller/SessionSyncCoordinator.kt` | SSE 事件中枢 | Task2 删 idle dropTempCleared+dropTempCleared；Task3 status 生产+删 message.created 未读；Task5 SSE 归档清理 |
| `ui/controller/SessionSwitcher.kt` | 切换会话 | Task2 删 previousWasBusyAndCleared + 简化 Step8 |
| `ui/AppStateSlices.kt` | `UnreadState`/`SessionListState` | Task2 删 `tempClearedUnread` 字段 |
| `ui/AppAction.kt` | reducer | Task2 HostStatePurged 删 tempCleared 行；Task5 SessionArchived 补清理 |
| `ui/SessionListActions.kt` | REST status 加载 | Task4 `mergeStatusSnapshot` 重连兜底 |
| `ui/ViewModelSupport.kt` | `crossSessionPendingCount` | Task6 question 改 tree-aware |
| `ui/chat/ChatScaffold.kt` | question 投影 | Task6 matchingQuestions 改当前 tree |
| `ui/chat/ChatSessionTabStrip.kt` | 顶部 tab `?` | Task6 root 聚合 |
| `ui/chat/SessionPickerSheet.kt` | picker `?` | Task6 root 聚合 |
| `ui/SessionViewModel.kt` | closeSession | （保留，无改） |
| `ui/SettingsViewModel.kt` | disconnectWorkdir | Task5 补 unread 清理 |
| 测试（6 文件） | fixture/断言 | Task2 清 tempCleared；各 task 补测试 |

---

## Task 1: 共享 session-tree 推导纯函数

**Files:**
- Create: `app/src/main/java/cn/vectory/ocdroid/ui/controller/SessionTree.kt`
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/SessionMutationActions.kt:169-176`（`sessionSubtreeIds` 升级为 internal 三源薄封装）
- Test: `app/src/test/java/cn/vectory/ocdroid/ui/controller/SessionSyncPureFunctionsTest.kt`（追加）

**Interfaces:**
- Produces（后续 task 全部依赖）:
  - `internal fun allSessionsById(sessions: List<Session>, directorySessions: Map<String,List<Session>>, childSessions: Map<String,List<Session>>): Map<String, Session>` — 三源去重合并为 id→Session
  - `internal fun rootIdOf(sessionId: String, sessionsById: Map<String, Session>): String?` — 沿 `parentId` 向上解析到 root（parentId==null）；未知 session 或环返回 null
  - `internal fun treeIds(rootId: String, sessionsById: Map<String, Session>): Set<String>` — root + 全部后代
  - `internal fun subtreeIds(rootId: String, sessions: List<Session>, directorySessions: Map<String,List<Session>>, childSessions: Map<String,List<Session>>): Set<String>` — 三源 subtree（= `treeIds(rootId, allSessionsById(...))`）

- [ ] **Step 1: 写失败测试**（追加到 `SessionSyncPureFunctionsTest.kt`）

```kotlin
@Test fun allSessionsById_merges_three_sources_dedup() {
    val root = session("A", parentId = null)
    val child = session("C", parentId = "A")
    val byId = allSessionsById(
        sessions = listOf(root),
        directorySessions = mapOf("w" to listOf(child)),
        childSessions = emptyMap(),
    )
    assertEquals(root, byId["A"])
    assertEquals(child, byId["C"])
}

@Test fun rootIdOf_walks_parent_chain() {
    val byId = mapOf("A" to session("A", null), "B" to session("B","A"), "C" to session("C","B"))
    assertEquals("A", rootIdOf("C", byId))
    assertEquals("A", rootIdOf("A", byId))
    assertNull(rootIdOf("X", byId))            // 未知
}

@Test fun rootIdOf_cycle_returns_null() {
    val byId = mapOf("A" to session("A","B"), "B" to session("B","A"))  // 环
    assertNull(rootIdOf("A", byId))
}

@Test fun treeIds_includes_root_and_all_descendants() {
    val byId = mapOf("A" to session("A",null), "B" to session("B","A"), "C" to session("C","B"), "Z" to session("Z",null))
    assertEquals(setOf("A","B","C"), treeIds("A", byId))
}

@Test fun subtreeIds_covers_three_sources() {
    val root = session("A", null); val child = session("C","A")
    val ids = subtreeIds("A", listOf(root), mapOf("w" to listOf(child)), emptyMap())
    assertEquals(setOf("A","C"), ids)
}
```
（`session(id, parentId)` 用现有 SeedFixture 的 helper；若签名不同，adapt 之。）

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SessionSyncPureFunctionsTest.allSessionsById_merges_three_sources_dedup" --tests "*.SessionSyncPureFunctionsTest.rootIdOf_walks_parent_chain" --tests "*.SessionSyncPureFunctionsTest.treeIds_includes_root_and_all_descendants" --tests "*.SessionSyncPureFunctionsTest.subtreeIds_covers_three_sources"`
Expected: 编译失败（unresolved reference）。

- [ ] **Step 3: 实现 SessionTree.kt**

```kotlin
package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Session

/** Merge the three session stores into a deduped id→Session map. */
internal fun allSessionsById(
    sessions: List<Session>,
    directorySessions: Map<String, List<Session>>,
    childSessions: Map<String, List<Session>>,
): Map<String, Session> = buildMap {
    sessions.forEach { putIfAbsent(it.id, it) }
    directorySessions.values.flatten().forEach { putIfAbsent(it.id, it) }
    childSessions.values.flatten().forEach { putIfAbsent(it.id, it) }
}

/** Walk parentId up to the root (parentId==null). null if unknown or cycle. */
internal fun rootIdOf(sessionId: String, sessionsById: Map<String, Session>): String? {
    var cur = sessionId
    val seen = HashSet<String>()
    while (true) {
        if (!seen.add(cur)) return null          // cycle guard
        val s = sessionsById[cur] ?: return null // unknown
        val p = s.parentId
        if (p == null) return cur                // reached root
        cur = p
    }
}

/** root + all descendants. */
internal fun treeIds(rootId: String, sessionsById: Map<String, Session>): Set<String> {
    val childrenByParent = sessionsById.values.groupBy { it.parentId }
    val out = LinkedHashSet<String>()
    fun collect(id: String) {
        if (!out.add(id)) return
        childrenByParent[id]?.forEach { collect(it.id) }
    }
    collect(rootId)
    return out
}

/** Three-source subtree helper (= treeIds over allSessionsById). */
internal fun subtreeIds(
    rootId: String,
    sessions: List<Session>,
    directorySessions: Map<String, List<Session>>,
    childSessions: Map<String, List<Session>>,
): Set<String> = treeIds(rootId, allSessionsById(sessions, directorySessions, childSessions))
```

- [ ] **Step 4: 升级 SessionMutationActions.kt:169-176 `sessionSubtreeIds`**

把 private `sessionSubtreeIds` 改为对新 `subtreeIds(...)` 的薄封装（保留 parentFirst 语义给现有归档调用），或直接让归档调用方改用新函数。最小破坏：保留旧签名内部委托：

```kotlin
private fun sessionSubtreeIds(sessions: List<Session>, rootId: String, parentFirst: Boolean): List<String> {
    val ids = subtreeIds(rootId, sessions, emptyMap(), emptyMap())
    return if (parentFirst) ids.toList() else ids.reversed()
}
```
（注：归档路径原本只传 sessions；若需三源，在 Task5 改调用点。此处仅去重逻辑收敛。）

- [ ] **Step 5: 跑测试验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SessionSyncPureFunctionsTest"`
Expected: PASS（新增 5 测试全过，原测试不回归）。

- [ ] **Step 6: 记录 diff**
```bash
git rev-parse HEAD   # 应仍为 base
git diff --stat
```

---

## Task 2: 删除 ping-pong 重标记 + `tempClearedUnread` 字段

**Files:**
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/controller/SessionSwitcher.kt:220-230, 391-408`
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/controller/SessionSyncCoordinator.kt:524-536, 1671-1676`
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/AppStateSlices.kt:358-362`
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/AppAction.kt:198-202`
- Modify (fixture 清理): `SessionSwitcherTest.kt`, `SessionSyncCoordinatorTest.kt`, `SessionSyncPureFunctionsTest.kt`, `AppActionReducerTest.kt`, `HostProfileControllerTest.kt`, `SeedFixture.kt`

**Interfaces:**
- Consumes: 无（独立）
- Produces: `UnreadState` 仅剩 `{ unreadSessions, lastViewedTime }`；`SessionSwitcher.switchTo` 不再读 `tempClearedUnread`/`sessionStatuses` 判定重标记。

- [ ] **Step 1: 写失败测试**（追加到 `SessionSwitcherTest.kt`）

> **grilling G2 修正**：原 `switchTo_clears_target_unread_and_does_not_remark_outgoing_busy` 单步设计无法区分新旧逻辑（A 初始在 unreadSessions 时两者结果相同）。改为：基础清除测试 + 两步 ping-pong 回归（能真正区分——旧逻辑在 switchTo(A) 后把 A 加入 tempClearedUnread，再 switchTo(B) 会 re-mark A 残留；新逻辑清零）。

```kotlin
@Test fun switchTo_clears_target_session_unread() {
    // 基础：进入某 session 清除其未读
    val ctx = newSwitcher(baseSeed().copy(
        currentSessionId = "CUR",
        unread = UnreadState(unreadSessions = setOf("A","CUR")),
    ))
    ctx.switchTo("A")
    assertFalse(ctx.slices.unread.value.unreadSessions.contains("A"))
}

@Test fun switchTo_AB_pingpong_both_clear() {
    // 回归故障2：A、B 均 busy，A→B 来回切换最终清零。
    // 旧逻辑：switchTo(A) 把 A 加入 tempClearedUnread；switchTo(B) 时
    // previousWasBusyAndCleared(A∈tempCleared ∩ busy) → re-mark A 残留。
    // 新逻辑：不重标 → 清零。
    val ctx = newSwitcher(baseSeed().copy(
        currentSessionId = null,
        unread = UnreadState(unreadSessions = setOf("A","B")),
        statuses = mapOf("A" to busyStatus, "B" to busyStatus),
    ))
    ctx.switchTo("A")
    assertEquals(setOf("B"), ctx.slices.unread.value.unreadSessions)
    ctx.switchTo("B")
    assertEquals(emptySet<String>(), ctx.slices.unread.value.unreadSessions)  // A 不回来
}
```
（`baseSeed()`/`newSwitcher()` 用现有 helper；`UnreadState` 两参构造因字段已删。）

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SessionSwitcherTest.switchTo_clears_target_unread_and_does_not_remark_outgoing_busy"`
Expected: FAIL（旧逻辑 re-mark A → `unreadSessions == {A}` 而非期望「A 被重标后只剩…」——实际新断言会因 `tempClearedUnread` 仍存在 + re-mark 导致 `{A}` vs 期望不匹配；同时字段未删编译可能仍过，断言失败）。

- [ ] **Step 3: 改 SessionSwitcher.kt**

删除 `L226-230` 的 `previousWasBusyAndCleared` 整块（保留 `previousSessionId`，`L234` cache capture 仍用它）。Step 8（`L391-408`）替换为：

```kotlin
// ── Step 8: Unread state machine + draft discard + openSessionIds ───
val now = clock()
slices.mutateUnread {
    it.copy(
        unreadSessions = it.unreadSessions - sessionId,
        lastViewedTime = it.lastViewedTime + (sessionId to now),
    )
}
```
更新 `L203-204` 注释：去掉「tempClearedUnread + re-mark busy」描述。

- [ ] **Step 4: 删除 tempClearedUnread 全部生产代码**
- `SessionSyncCoordinator.kt:524-536`：删除整个「idle 非当前 tempCleared → dropTempCleared」`if` 块及其注释。
- `SessionSyncCoordinator.kt:1671-1676`：删除 `dropTempCleared` 纯函数 + KDoc。
- `AppStateSlices.kt:358-362`：`UnreadState` 删除 `tempClearedUnread` 字段行。
- `AppAction.kt:198-202`：`HostStatePurged` reducer 删除 `tempClearedUnread = emptySet(),` 行。

- [ ] **Step 5: 清理测试 fixture**（编译修复）
- `SeedFixture.kt`：`unread` 构造删 `tempClearedUnread` 形参/字段。
- `SessionSwitcherTest.kt`/`SessionSyncCoordinatorTest.kt`/`SessionSyncPureFunctionsTest.kt`/`AppActionReducerTest.kt`/`HostProfileControllerTest.kt`：删除所有 `tempClearedUnread = ...` 赋值；删除/改写以 re-mark/dropTempCleared 为断言对象的测试（改为「不重标」断言，见 Step1）。

- [ ] **Step 6: 跑测试验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SessionSwitcherTest" --tests "*.SessionSyncPureFunctionsTest" --tests "*.AppActionReducerTest" --tests "*.HostProfileControllerTest"`
Expected: PASS。

- [ ] **Step 7: 记录 diff**
```bash
git diff --stat
```

---

## Task 3: SSE `session.status` 未读生产 + 删 `message.created` 未读

**Files:**
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/controller/SessionSyncCoordinator.kt:489-567`（status 分支）, `569-609`（message.created 分支）, `1659-1669`（applyMarkSessionUnread KDoc）
- Test: `SessionSyncCoordinatorTest.kt`

**Interfaces:**
- Consumes: Task1 `allSessionsById`（判 isKnownRoot）
- Produces: 非当前 root session busy→idle → 写 unreadSessions；message.* 不再生产未读。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun status_busy_to_idle_root_noncurrent_marks_unread() {
    val ctx = newCoordinator(baseSeed().copy(
        currentSessionId = "CUR",
        sessions = listOf(session("A", parentId=null), session("CUR", null)),
        statuses = mapOf("A" to busyStatus),       // 旧状态 busy
    ))
    ctx.handleEvent(statusEvent("A", idleStatus))
    assertTrue(ctx.slices.unread.value.unreadSessions.contains("A"))
}

@Test fun status_busy_to_idle_root_current_not_marked() {
    val ctx = newCoordinator(baseSeed().copy(currentSessionId="A", sessions=listOf(session("A",null)), statuses=mapOf("A" to busyStatus)))
    ctx.handleEvent(statusEvent("A", idleStatus))
    assertFalse(ctx.slices.unread.value.unreadSessions.contains("A"))
}

@Test fun status_busy_to_idle_child_not_marked() {
    val ctx = newCoordinator(baseSeed().copy(
        currentSessionId="CUR",
        sessions=listOf(session("CHILD","A"), session("A",null), session("CUR",null)),
        statuses=mapOf("CHILD" to busyStatus)))
    ctx.handleEvent(statusEvent("CHILD", idleStatus))
    assertFalse(ctx.slices.unread.value.unreadSessions.contains("CHILD"))
}

@Test fun status_busy_to_idle_unknown_session_not_marked() {
    val ctx = newCoordinator(baseSeed().copy(currentSessionId="CUR", sessions=listOf(session("CUR",null)), statuses=mapOf("GHOST" to busyStatus)))
    ctx.handleEvent(statusEvent("GHOST", idleStatus))
    assertFalse(ctx.slices.unread.value.unreadSessions.contains("GHOST"))
}

@Test fun status_idle_to_idle_not_marked() {
    val ctx = newCoordinator(baseSeed().copy(currentSessionId="CUR", sessions=listOf(session("A",null)), statuses=mapOf("A" to idleStatus)))
    ctx.handleEvent(statusEvent("A", idleStatus))
    assertFalse(ctx.slices.unread.value.unreadSessions.contains("A"))
}

@Test fun message_created_noncurrent_does_not_mark_unread() {
    val ctx = newCoordinator(baseSeed().copy(currentSessionId="CUR", sessions=listOf(session("A",null), session("CUR",null))))
    ctx.handleEvent(messageCreatedEvent("A"))
    assertFalse(ctx.slices.unread.value.unreadSessions.contains("A"))
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SessionSyncCoordinatorTest.status_busy_to_idle_root_noncurrent_marks_unread" --tests "*.SessionSyncCoordinatorTest.message_created_noncurrent_does_not_mark_unread"`
Expected: FAIL（status 分支当前不标未读；message.created 当前会标）。

- [ ] **Step 3: 改 session.status 分支（L489-567）**

在 `parseSessionStatusEvent` 成功后、`mutateSessionList { applySessionStatus }` **之前**，快照旧状态与 root 判定：

```kotlin
val statusEvent = parseSessionStatusEvent(event)
if (statusEvent != null) {
    val oldStatus = slices.sessionList.value.sessionStatuses[statusEvent.sessionId]
    val wasBusy = oldStatus?.isBusy == true
    val nowIdle = statusEvent.status.isIdle
    slices.mutateSessionList {
        it.applySessionStatus(statusEvent.sessionId, statusEvent.status).first
    }
    val statusEffects = mutableListOf<SseSideEffect>()
    val chatSnap = slices.chat.value
    val isCurrent = statusEvent.sessionId == chatSnap.currentSessionId
    // §lifecycle-unread: root busy→idle（非当前）= 任务完成 → 标未读
    if (wasBusy && nowIdle && !isCurrent) {
        val sessionsById = allSessionsById(
            slices.sessionList.value.sessions,
            slices.sessionList.value.directorySessions,
            slices.sessionList.value.childSessions,
        )
        val s = sessionsById[statusEvent.sessionId]
        if (s != null && s.parentId == null) {   // 已知 root 才标
            markSessionUnread(statusEvent.sessionId)
        }
    }
    if (statusEvent.status.isBusy && isCurrent) {
        statusEffects.add(SseSideEffect.ReloadMessages(statusEvent.sessionId, resetLimit = true))
    }
    // （Task2 已删除 tempCleared idle 分支）
    if (statusEvent.status.isIdle && isCurrent) {
        val overlayNonEmpty = chatSnap.streamingPartTexts.isNotEmpty() || chatSnap.streamingReasoningPart != null
        if (overlayNonEmpty) statusEffects.add(SseSideEffect.ReloadMessages(statusEvent.sessionId, resetLimit = true))
    }
    applySseSideEffects(statusEffects)
} else { applySseSideEffects(listOf(SseSideEffect.ReportNonFatal("Ignoring invalid session.status payload"))) }
```

- [ ] **Step 4: 改 message.created 分支（L569-609）**

删除未读生产（`L603-607` 的 `else if (sessionId != null) { markSessionUnread(sessionId) }`）。保留时间戳 bump（`L585-597`）与当前 session reload（`L601-602`）。更新 `L578` DebugLog 与分支 KDoc：明确「未读唯一由 session lifecycle completion 生产；本分支仅时间戳+reload 兼容」。

- [ ] **Step 5: 更新 applyMarkSessionUnread KDoc（L1659-1669）**

注释从「message.created (out-of-band)」改为「session lifecycle completion（root busy→idle）或 REST 重连兜底」。

- [ ] **Step 6: 跑测试验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SessionSyncCoordinatorTest"`
Expected: PASS（新增 6 测试 + 原测试不回归）。

- [ ] **Step 7: 记录 diff**
```bash
git diff --stat
```

---

## Task 4: REST 重连兜底（本地 busy + 快照缺失）

**Files:**
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/SessionListActions.kt:337-387`（launchLoadSessionStatus + mergeStatusSnapshot）
- Test: `SessionListActionsTest.kt`

**Interfaces:**
- Consumes: Task1 `allSessionsById`、Task3 `markSessionUnread` 语义
- Produces: 重连时对「本地 busy 且 REST 快照缺失」的 root 非当前 session 补标未读；首次加载（`localBefore` 全空）不触发。

> **注意**：`markSessionUnread` 是 `SessionSyncCoordinator` 的 private 方法。本 task 在 `launchLoadSessionStatus` 内直接用 `slices.mutateUnread { ... }` 写 unread（与 SSC 同样的 `applyMarkSessionUnread` 纯函数，将其提升为 internal 复用，或在本处内联等价逻辑）。首选：把 `applyMarkSessionUnread` 纯函数已是 internal（Task3 确认），直接 `slices.mutateUnread { it.applyMarkSessionUnread(id, currentSessionId).first }`。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun reconnect_known_busy_missing_in_rest_marks_unread_root_noncurrent() {
    val localBefore = mapOf("A" to busyStatus)   // 本地已知 busy
    val restSnapshot = emptyMap<String,SessionStatus>()  // REST 不含 A（已完成）
    val merged = mergeStatusSnapshot(localBefore, localBefore, restSnapshot)
    // 断言由 launchLoadSessionStatus 调用点产生副作用——此处测纯函数+集成
    assertTrue(merged.isEmpty())  // busy 被清除
    // 集成：调用 launchLoadSessionStatus 后 unreadSessions 含 A（root、非当前）
}

@Test fun reconnect_first_snapshot_does_not_batch_mark() {
    val localBefore = emptyMap<String,SessionStatus>()  // 首次
    val restSnapshot = emptyMap()
    // 即使本地无 busy 证据，不产生未读
}
```
（具体集成测试在 `SessionListActionsTest.kt` 用 FakeRepository + slices 断言 unread。）

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SessionListActionsTest.reconnect_known_busy_missing_in_rest_marks_unread_root_noncurrent"`
Expected: FAIL（当前不标未读）。

- [ ] **Step 3: 改 launchLoadSessionStatus（L342-366）**

> **grilling G1 修正**：`completedRoots` 必须用 `localBefore`（REST 发起前快照）判定 wasBusy，**不可用 `sl.sessionStatuses`**。否则「REST 在途期间 session 从 idle 变 busy、REST 快照（更早）缺失该 id」会被误判为完成。`localBefore` 是 L346 在 `scope.launch` 外捕获的发起前快照，反映「REST 发起时哪些 session 确为 busy」，可靠。

在 `getSessionStatus().onSuccess { statuses -> ... }` 内、`mergeStatusSnapshot` 调用前，识别完成候选并补标：

```kotlin
onSuccess { statuses ->
    slices.mutateSessionList { sl ->
        if (myEpoch != statusLoadEpoch.get()) { ...return@mutateSessionList sl }
        // §reconnect-unread: localBefore(发起前)已知 busy、REST 权威快照缺失 = 断线期间完成。
        // 用 localBefore 而非 sl.sessionStatuses，避免「REST 在途期间变 busy」误判。
        val currentSessionId = slices.chat.value.currentSessionId
        val sessionsById = allSessionsById(sl.sessions, sl.directorySessions, sl.childSessions)
        val completedRoots = localBefore.entries
            .filter { (id, st) -> st.isBusy && id != currentSessionId && id !in statuses }
            .mapNotNull { (id, _) -> sessionsById[id]?.takeIf { it.parentId == null }?.id }
        val merged = mergeStatusSnapshot(localBefore, sl.sessionStatuses, statuses)
        if (completedRoots.isNotEmpty()) {
            slices.mutateUnread { u ->
                completedRoots.fold(u) { acc, id -> acc.applyMarkSessionUnread(id, currentSessionId).first }
            }
        }
        sl.copy(sessionStatuses = merged)
    }
}
```
> 注：`applyMarkSessionUnread` 在 Task3 已 internal；`mutateUnread` 与 `mutateSessionList` 各自原子，Main.immediate 串行无 TOCTOU。`localBefore` 全空（首次加载）时 `localBefore.entries` 自然无 busy → `completedRoots` 为空 → 不批量标。

- [ ] **Step 4: 跑测试验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SessionListActionsTest"`
Expected: PASS。

- [ ] **Step 5: 记录 diff**
```bash
git diff --stat
```

---

## Task 5: 未读清除生命周期补全（归档/删除/disconnectWorkdir）

**Files:**
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/AppAction.kt:155-163`（`SessionArchived` reducer — SSE 归档清理点；本地归档不经此）
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/SessionMutationActions.kt:87-166`（本地归档 onSuccess 补 subtree 清理）, `178-243`（删除：改为删前快照 subtree）
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/SessionViewModel.kt`（新增 `clearUnreadForWorkdir(workdir)`）
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/files/FilesScreen.kt:431`（disconnect 时调 `sessionVM.clearUnreadForWorkdir`）
- Test: `SessionMutationActionsTest.kt`, `AppActionReducerTest.kt`, `SessionViewModelTest.kt`(若有)

> **grilling G3/G5 修正**：SSE 归档走 `SessionSyncCoordinator.kt:464` dispatch `AppAction.SessionArchived` → reducer（`AppAction.kt:155-163`）调 `applyArchiveEviction`。**清理加在 reducer**（同一次原子状态转换，避免 torn state），不在 SSC。本地归档（`launchSetSessionArchived`）直接 mutateSessionList 不经 reducer，需单独补。两路径各自补 unread+question。
> **grilling G12 修正**：删除改为「删前快照 subtree、成功后清整树」，防御 server 级联删后代导致 child question 残留。
> **disconnectWorkdir 决策（用户选 d）**：SettingsViewModel 不持有 slices；在 SessionViewModel 加 `clearUnreadForWorkdir`，FilesScreen 协调调用。

**Interfaces:**
- Consumes: Task1 `subtreeIds`、`allSessionsById`
- Produces: 新增 `internal fun UnreadState.removeSessions(ids: Set<String>)` 纯函数（放 SessionTree.kt 或 AppStateSlices 伴生）；归档/删除/disconnectWorkdir 清 unread + pendingQuestions（subtree 范围）。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun archive_root_clears_subtree_unread_and_questions() {
    // root A 有未读 + 子 C 有 pending question；归档 A 后两者皆清
    val ctx = newMutationEnv(baseSeed().copy(
        sessions = listOf(session("A",null), session("C","A")),
        unread = UnreadState(setOf("A")),
        pendingQuestions = listOf(question("q1","C"))))
    ctx.archive("A", archived = true)
    assertFalse(ctx.slices.unread.value.unreadSessions.contains("A"))
    assertTrue(ctx.slices.sessionList.value.pendingQuestions.none { it.sessionId == "C" })
}

@Test fun delete_session_clears_its_unread_and_questions() {
    val ctx = newMutationEnv(baseSeed().copy(
        sessions = listOf(session("A",null)),
        unread = UnreadState(setOf("A")),
        pendingQuestions = listOf(question("q1","A"))))
    ctx.delete("A")
    assertFalse(ctx.slices.unread.value.unreadSessions.contains("A"))
    assertTrue(ctx.slices.sessionList.value.pendingQuestions.none { it.id == "q1" })
}

@Test fun sse_archive_eviction_clears_unread_and_questions() {
    val ctx = newCoordinator(baseSeed().copy(
        sessions = listOf(session("A",null)),
        unread = UnreadState(setOf("A")),
        pendingQuestions = listOf(question("q1","A"))))
    ctx.handleEvent(sessionUpdatedArchivedEvent("A"))
    assertFalse(ctx.slices.unread.value.unreadSessions.contains("A"))
    assertTrue(ctx.slices.sessionList.value.pendingQuestions.none { it.sessionId == "A" })
}

@Test fun clearUnreadForWorkdir_clears_only_that_workdir_sessions() {
    // directorySessions["w"] = [A], A 未读；其他 workdir session Z 也未读
    val vm = newSessionViewModel(baseSeed().copy(
        directorySessions = mapOf("w" to listOf(session("A", null))),
        unread = UnreadState(setOf("A","Z"))))
    vm.clearUnreadForWorkdir("w")
    assertFalse(vm.store.unreadFlow.value.unreadSessions.contains("A"))
    assertTrue(vm.store.unreadFlow.value.unreadSessions.contains("Z"))  // 其他 workdir 不受影响
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SessionMutationActionsTest.archive_root_clears_subtree_unread_and_questions" --tests "*.SessionSyncCoordinatorTest.sse_archive_eviction_clears_unread_and_questions"`
Expected: FAIL。

- [ ] **Step 3: 新增纯函数 `UnreadState.removeSessions`**

放 `SessionTree.kt`（或紧邻 UnreadState）：
```kotlin
internal fun UnreadState.removeSessions(ids: Set<String>): UnreadState = copy(
    unreadSessions = unreadSessions - ids,
    lastViewedTime = lastViewedTime.filterKeys { it !in ids },
)
```

- [ ] **Step 4: 本地归档 `launchSetSessionArchived`（L87-166）补清理**

在每个 id 的 `onSuccess` 内、归档（`isArchive`）分支，补（用三源 subtree）：
```kotlin
if (isArchive) {
    val subtree = subtreeIds(id, currentSessions, currentDirSessions, slices.sessionList.value.childSessions)
    slices.mutateUnread { it.removeSessions(subtree) }
    slices.mutateSessionList { sl ->
        sl.copy(pendingQuestions = sl.pendingQuestions.filter { q -> q.sessionId !in subtree })
    }
}
```
（注：归档是逐 id；若需整树一次性，可在循环前算 `subtreeIds(sessionId,...)` 后统一清。最小改动：循环内逐 id 清该 id。）

- [ ] **Step 5: 删除 `launchDeleteSession`（L178-243）补清理（G12：删前快照 subtree）**

在 `scope.launch` 内、`repository.deleteSession(sessionId)` 调用**前**快照 subtree（此时 session 元数据仍在），`onSuccess` 后清整树：

```kotlin
scope.launch {
    // §delete-subtree: 删前快照整树（防御 server 级联删后代导致 child 残留）
    val slSnap = slices.sessionList.value
    val removedIds = subtreeIds(sessionId, slSnap.sessions, slSnap.directorySessions, slSnap.childSessions)
    repository.deleteSession(sessionId)
        .onSuccess {
            val currentSessions = slices.sessionList.value.sessions
            val currentDirSessions = slices.sessionList.value.directorySessions
            val newSessions = currentSessions.filter { s -> s.id !in removedIds }
            val newDirSessions = currentDirSessions
                .mapValues { (_, list) -> list.filter { s -> s.id !in removedIds } }
                .filterValues { it.isNotEmpty() }
            slices.mutateSessionList { sl ->
                sl.copy(
                    sessions = newSessions,
                    directorySessions = newDirSessions,
                    pendingQuestions = sl.pendingQuestions.filter { it.sessionId !in removedIds },
                )
            }
            slices.mutateUnread { it.removeSessions(removedIds) }
            // （后续 currentSessionId 回退、EvictSession 等保持现有逻辑）
            ... // 现有 currentId 回退 + emitEffect 不变
        }
        .onFailure { ... }
}
```

- [ ] **Step 6: SSE 归档 reducer（`AppAction.kt:155-163`）补清理（G3/G5）**

SSE 归档经 SSC:464 dispatch → reducer。在 reducer 的 `SessionArchived` 分支同一次状态转换里清 unread + question（单 id，与 applyArchiveEviction 语义一致；后代归档由各自 session.updated 事件逐个清理）：

```kotlin
is AppAction.SessionArchived -> {
    val isCurrent = state.chat.currentSessionId == action.session.id
    val newSessionList = state.sessionList.applyArchiveEviction(action.session, action.openSessionIds).first
    val newChat = if (isCurrent) state.chat.applyArchivedChatClear().first else state.chat
    val archivedId = action.session.id
    // §lifecycle-unread: archived session 的 unread + pendingQuestions 清理
    val cleanedQuestions = newSessionList.pendingQuestions.filter { it.sessionId != archivedId }
    val newUnread = state.unread.removeSessions(setOf(archivedId))
    state.copy(
        sessionList = newSessionList.copy(pendingQuestions = cleanedQuestions),
        chat = newChat,
        unread = newUnread,
    )
}
```

- [ ] **Step 7: disconnectWorkdir 清理（用户决策 d：SessionVM + FilesScreen 协调）**

在 `SessionViewModel` 新增方法（SessionViewModel 持有 store）：

```kotlin
fun clearUnreadForWorkdir(workdir: String) {
    val ids = store.sessionListFlow.value.directorySessions[workdir]?.map { it.id }?.toSet().orEmpty()
    if (ids.isNotEmpty()) {
        store.mutateUnread { it.removeSessions(ids) }
    }
}
```

`FilesScreen.kt:431` disconnect 调用处补协调调用：

```kotlin
settingsVM.disconnectWorkdir(workdir)
sessionVM.clearUnreadForWorkdir(workdir)   // §lifecycle-unread: 清该 workdir session 未读
```

- [ ] **Step 8: 跑测试验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SessionMutationActionsTest" --tests "*.AppActionReducerTest"`
Expected: PASS（归档 reducer 清理在 AppActionReducerTest；本地归档/删除在 SessionMutationActionsTest）。

- [ ] **Step 9: 记录 diff**
```bash
git diff --stat
```

---

## Task 6: question tree 聚合 UI

**Files:**
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/ViewModelSupport.kt:247-252`（crossSessionPendingCount）
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/chat/ChatScaffold.kt:242-245`（matchingQuestions）, `411-412, 899-900`（questionSessionIds 传参）
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/chat/ChatSessionTabStrip.kt:301-312`（tab `?`）
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/chat/SessionPickerSheet.kt:198-220`（picker `?`）
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/sessions/SessionsScreen.kt`（对齐 rootHasPending）
- Test: `SessionSyncPureFunctionsTest.kt`（聚合纯函数）+ 现有 UI 测试

**Interfaces:**
- Consumes: Task1 `rootIdOf`/`treeIds`/`allSessionsById`
- Produces:
  - `internal fun questionRootIds(pendingQuestions, sessionsById): Set<String>` — 每个 question 的 session 向上解析到的 root id 集合
  - `internal fun questionsInTree(rootId, pendingQuestions, sessionsById): List<QuestionRequest>` — 当前 root tree 内的 question（保留原 sessionId/id）
  - `crossSessionPendingCount`：permission 不变；question = 不在当前 root tree 内的数量

- [ ] **Step 1: 写失败测试（纯函数）**

```kotlin
@Test fun questionRootIds_aggregates_child_question_to_root() {
    val byId = mapOf("A" to session("A",null), "C" to session("C","A"))
    val qs = listOf(question("q1","C"))
    assertEquals(setOf("A"), questionRootIds(qs, byId))
}

@Test fun questionsInTree_returns_child_questions_preserving_id() {
    val byId = mapOf("A" to session("A",null), "C" to session("C","A"))
    val qs = listOf(question("q1","C"), question("q2","Z"))  // Z 不在 A 树
    val inTree = questionsInTree("A", qs, byId)
    assertEquals(1, inTree.size)
    assertEquals("C", inTree[0].sessionId)  // 保留真实 sessionId
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SessionSyncPureFunctionsTest.questionRootIds_aggregates_child_question_to_root"`
Expected: FAIL（unresolved）。

- [ ] **Step 3: 实现聚合纯函数**（SessionTree.kt）

```kotlin
internal fun questionRootIds(
    pendingQuestions: List<cn.vectory.ocdroid.data.model.QuestionRequest>,
    sessionsById: Map<String, Session>,
): Set<String> = pendingQuestions.mapNotNull { rootIdOf(it.sessionId, sessionsById) }.toSet()

internal fun questionsInTree(
    rootId: String,
    pendingQuestions: List<cn.vectory.ocdroid.data.model.QuestionRequest>,
    sessionsById: Map<String, Session>,
): List<cn.vectory.ocdroid.data.model.QuestionRequest> {
    val tree = treeIds(rootId, sessionsById)
    return pendingQuestions.filter { it.sessionId in tree }
}
```

- [ ] **Step 4: 改 crossSessionPendingCount（ViewModelSupport.kt:247-252）**

签名增加 sessionsById（或直接接收 SessionListState 自算）；permission 保持精确，question 改 tree-aware：
```kotlin
internal fun crossSessionPendingCount(
    state: SessionListState,
    currentSessionId: String?
): Int {
    val byId = allSessionsById(state.sessions, state.directorySessions, state.childSessions)
    val currentRoot = currentSessionId?.let { rootIdOf(it, byId) }
    val currentTree = currentRoot?.let { treeIds(it, byId) }.orEmpty()
    val permissions = state.pendingPermissions.count { it.sessionId != currentSessionId }   // 保持精确
    val questions = state.pendingQuestions.count { it.sessionId !in currentTree }           // tree-aware
    return permissions + questions
}
```

- [ ] **Step 5: 改 ChatScaffold matchingQuestions（L242-245）**

```kotlin
val sessionsById = remember(sessionList.sessions, sessionList.directorySessions, sessionList.childSessions) {
    allSessionsById(sessionList.sessions, sessionList.directorySessions, sessionList.childSessions)
}
val matchingQuestions = remember(sessionList.pendingQuestions, chat.currentSessionId, sessionsById) {
    val root = chat.currentSessionId?.let { rootIdOf(it, sessionsById) }
    if (root != null) questionsInTree(root, sessionList.pendingQuestions, sessionsById)
    else emptyList()
}
```
（保留 `pendingQuestion = matchingQuestions.firstOrNull()`；QuestionCard 回答用 question 真实 sessionId/id，不改。）

- [ ] **Step 6: 改 tab/picker/Sessions `?` 投影**

- `ChatScaffold.kt:411-412, 899-900`：`questionSessionIds` 改为 `questionRootIds(sessionList.pendingQuestions, sessionsById)`（已是 root id 集合，tab/picker 直接 `root.id in questionSessionIds`）。
- `ChatSessionTabStrip.kt:301-312`：判定改为 `session.id in questionRootIds`（接收的已是 root 集合，逻辑不变，仅数据源换）。
- `SessionPickerSheet.kt:198-220`：同上。
- `SessionsScreen.kt`：确认 `rootHasPending` 与新聚合一致（若已有则复用，避免两套实现）。

- [ ] **Step 7: 跑测试验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SessionSyncPureFunctionsTest" --tests "*.ChatViewModelTest"`
Expected: PASS。

- [ ] **Step 8: 记录 diff**
```bash
git diff --stat
```

---

## Task 7: 最终校验 + 整体回归

**Files:** 无新改（仅运行校验，修复任何遗留编译/测试/lint）

- [ ] **Step 1: 全量编译 + 单测**
Run: `./scripts/check.sh`
Expected: PASS（exit 0）。若失败，定位 task 回流修复。

- [ ] **Step 2: full 校验（lint + 覆盖率）**
Run: `./scripts/check.sh --full`
Expected: PASS。

- [ ] **Step 3: 整体 diff 自检**
```bash
git diff --stat b1f66d79c41f58e28b95b51e483bd6cd4ae0f985
```
确认：UnreadState 无 tempClearedUnread；无 dropTempCleared 引用；无 previousWasBusyAndCleared；message.created 分支无 markSessionUnread；SessionTree.kt 存在且被引用。

- [ ] **Step 4: 记录最终 diff**（不 commit）
```bash
git diff --stat
```

---

## Self-Review（plan 作者自查）

**1. Spec coverage:**
- 3.1 未读生产 → Task3 ✓
- 3.2 REST 重连兜底 → Task4 ✓
- 3.3 删 ping-pong + tempClearedUnread → Task2 ✓
- 3.4 message.created 保留+去未读 → Task3 Step4 ✓
- 3.5 生命周期清理（归档两路径/删除/disconnectWorkdir/host） → Task5 ✓（host 切换 HostStatePurged 已在 Task2 保留清理）
- 3.6 question tree 聚合 → Task6 ✓
- 冷重启窗口（接受丢失 + question 不丢） → 约束已声明，question 走 REST 不受影响 ✓
- 一次 commit → 全局约束 ✓

**2. Placeholder scan:** 无 TBD/TODO；每 step 有具体函数/断言/命令。（`question(id,sid)`/`session(id,pid)`/`statusEvent`/`messageCreatedEvent` 等为测试 helper，implementer 按现有 SeedFixture 适配——已在 step 注明。）

**3. Type consistency:** `removeSessions`（Task5）/ `questionRootIds`/`questionsInTree`（Task6）/ `allSessionsById`/`rootIdOf`/`treeIds`/`subtreeIds`（Task1）跨 task 名称一致。`UnreadState` 在 Task2 删字段后，后续 task 构造均用两参——Step 已注明。

**4. 顺序依赖:** Task1（基础）→ Task2（删字段，解除后续 UnreadState 构造耦合）→ Task3/4（生产）→ Task5/6（依赖 Task1）→ Task7（校验）。无环。

---

## Grilling 修正记录（阶段 3 压测产出）

| # | 缺陷 | 修正 | 落点 |
|---|---|---|---|
| G1 | Task4 `completedRoots` 用 `sl.sessionStatuses` 会把「REST 在途期间变 busy、快照缺失」误判为完成 | 改用 `localBefore`（REST 发起前快照）判定 wasBusy | Task4 Step3 |
| G2 | Task2 Step1 单步测试无法区分新旧逻辑（A 初始在 unreadSessions 时两者结果相同） | 删除有误测试，保留两步 ping-pong 回归 + 加基础清除测试 | Task2 Step1 |
| G3/G5 | Task5 SSE 归档清理点误标为 SSC 内 | 精确定位到 `AppAction.kt:155-163` reducer（SessionArchived 分支，同一次原子转换清 unread+question） | Task5 Files+Step6 |
| G12 | Task5 删除只清单 id，server 级联删后代会致 child question 残留 | 改为删前快照 subtree、成功后清整树 | Task5 Step5 |
| disconnect gap | SettingsViewModel 不持有 slices，无法 mutateUnread | 用户决策方案 d：SessionViewModel 加 `clearUnreadForWorkdir`，FilesScreen 协调调用 | Task5 Step7 |

grilling 另审查确认（无需改）：Task3 status 时序（busy 写 statuses 后 idle 能读到 wasBusy）✓；childSessions key=parentId 不影响 allSessionsById 合并（取 values flatten）✓；SliceFlows.mutateUnread 可用 ✓；rootIdOf/treeIds 环检测 ✓；crossSessionPendingCount currentSessionId=null 边界 ✓。
