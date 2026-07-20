# oc-slimapi v0.2.2 客户端适配 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use ocmar-subagent-driven-development (recommended) or ocmar-executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 ocdroid 客户端落地 oc-slimapi v0.2.2 要求的 4 项适配——P0 tie-break watermark 升级为 `(updatedAt, messageID)` tuple 字典序、P1 q/p `scope` 消费（修 N==0 误清 stale）、P2a `/sessions` 列表错误体 code-based、P2b directory 客户端规范化去重。

**Architecture:** 纯客户端 repo/UI-controller 层改动，无服务端 wire 变更。P0 为 correctness-critical 核心，引入单一 `compareWatermark` 纯函数统一 4 个 watermark 比较站点（onReconcileSuccess / needsReconcile / needsCatchUp / reduceSlimDigest）+ 修 `bumpUpdatedAt` 不对称；P1/P2a/P2b 各自独立、互不依赖 P0。

**Tech Stack:** Kotlin, JUnit 4（`org.junit.Assert.*` + `@Test`），Retrofit + kotlinx.serialization，无 Room/DAO（in-memory StateFlow slice）。

## Global Constraints

- **spec 权威**：`docs/ocmar/specs/2026-07-20-slimapi-v022-client-adapt-design.md`。本 plan 的 task 边界与 spec §3-§4 一一对应，不得偏离。
- **设备纪律**：UI/插桩测试仅模拟器（`./scripts/emulator.sh status`→未运行才 start，用完 stop）；本批改动纯数据/repo 层，以**单测**验证为主，不要求模拟器。
- **改动校验必做**：每个 task 完成后 `./scripts/check.sh` 通过（编译 + 单测，EXIT=0）；等价 LSP 自检（服务端 LSP 已关）。
- **不 commit**：ocmar 默认；仅 working-tree 改动 + per-task diff 记录。commit/release 仅用户显式触发。
- **wire 不变**：纯客户端；服务端 wire 仍 `1`，v0.2.2 已部署。
- **messageID 单调性前提**：已由 `packages/opencode/src/id/id.ts` 源码核实（`msg_` + 12hex(`ts*4096+counter`) + 随机尾，ascending，同 ms counter 自增 → 字典序严格单调）。P0 tuple 处方据此安全。

---

## File Structure

| 文件 | 职责 | task |
|---|---|---|
| `app/src/main/java/cn/vectory/ocdroid/data/repository/SlimapiResync.kt` | watermark 纯函数（`compareWatermark` 新增 + `onReconcileSuccess`/`needsReconcile` tuple 化） | T1 |
| `app/src/main/java/cn/vectory/ocdroid/data/repository/SlimapiProbe.kt` | `needsCatchUp` tuple 化 | T1 |
| `app/src/main/java/cn/vectory/ocdroid/data/repository/SlimSseReducer.kt` | `reduceSlimDigest` trigger + `bumpUpdatedAt` 对称化 | T1 |
| `app/src/test/java/cn/vectory/ocdroid/data/repository/SlimapiResyncTest.kt` | tie/atomic-pair 测试行为反转重写 + 新增 tuple 收敛测试 | T1 |
| `app/src/main/java/cn/vectory/ocdroid/data/model/Slimapi.kt` | `SlimapiScope` DTO + q/p aggregation 加 `scope` 字段 | T2 |
| `app/src/main/java/cn/vectory/ocdroid/data/repository/OpenCodeRepository.kt` | `aggregationOutcome` 收 scope；`getSlimapiSessions` code-based + `parseErrorCode`→internal | T2, T3 |
| `app/src/main/java/cn/vectory/ocdroid/ui/controller/SessionSyncCoordinator.kt` | `applyAggregationOutcome` scope==0 gating | T2 |
| `app/src/main/java/cn/vectory/ocdroid/ui/AppCoreOrchestration.kt` | `computeQuestionFanOutWorkdirs` normalize-dedup | T4 |
| `app/src/main/java/cn/vectory/ocdroid/util/WorkdirPaths.kt` | 新增 server-facing `normalizeDirectory`（根 `/` 保留），不改既有 `normalize` 契约 | T4 |

> **写区重叠**：T2 与 T3 都改 `OpenCodeRepository.kt`（不同函数：`aggregationOutcome`(:2434) vs `getSlimapiSessions`(:2361)/`parseErrorCode`(:2338)），**顺序执行**避免冲突。T4 独立文件。

---

## Task 1: P0 — tie-break `(updatedAt, messageID)` tuple 字典序 watermark

**Files:**
- Modify: `app/src/main/java/cn/vectory/ocdroid/data/repository/SlimapiResync.kt`（新增 `compareWatermark`；改 `onReconcileSuccess` `:135-195`、`needsReconcile` `:67-83`）
- Modify: `app/src/main/java/cn/vectory/ocdroid/data/repository/SlimapiProbe.kt`（`needsCatchUp` `:105-118`）
- Modify: `app/src/main/java/cn/vectory/ocdroid/data/repository/SlimSseReducer.kt`（`reduceSlimDigest` trigger `:368-383`、`bumpUpdatedAt` `:192-200`）
- Test: `app/src/test/java/cn/vectory/ocdroid/data/repository/SlimapiResyncTest.kt`（重写 `:151-414` tie/atomic-pair + 新增）

**Interfaces:**
- Consumes: `SlimSessionState`（`SlimSseReducer.kt:67-140`）的 4 watermark 字段，形状不变。
- Produces: `internal fun compareWatermark(tsA: Long?, idA: String?, tsB: Long?, idB: String?): Int`（top-level，`SlimapiResync.kt`）。同 package（`data/repository/`）的 `SlimapiProbe` / `SlimSseReducer` 直接调用。

**Acceptance Criteria:**
- `T1-C1`: `compareWatermark` 字典序：`(200,"m2") vs (200,"m1")` → 正；`(200,"m1") vs (200,"m2")` → 负；`(200,"m1") vs (200,"m1")` → 0；`(200,*) vs (100,*)` → 正（ts 主导）；`(null,*) vs (100,*)` → 负（null ts=最旧）；`(200,null) vs (200,"m1")` → 负（null id=最旧，仅 ts 相等时）。测试 `SlimapiResyncTest::test compareWatermark lexicographic and null ordering`。
- `T1-C2`: `onReconcileSuccess`——equal ts + 更大 id → 推进到 `(ts, largerId)`；equal ts + 更小 id → 保留 prior；strict ts 推进 → 搬 maxWithOrNull 选出的 (ts,id)。重写 `SlimapiResyncTest` 现有 "equal ts + different id → retain prior" 断言为反转语义。
- `T1-C3`: `needsReconcile`——`(remoteTs,remoteId)` tuple `>` `(localTs,localId)` 才 true；localTs==null && remoteTs!=null → true（初始）。测试覆盖 remote id 落后（stale digest）→ false（不误 reconcile）。
- `T1-C4`: `needsCatchUp`（SlimapiProbe）+ `reduceSlimDigest` fetch trigger 均用 `compareWatermark`，不再有裸 `ts >` 或 `id !=` 的 OR-of-two。
- `T1-C5`: `bumpUpdatedAt`（`SlimSseReducer.kt:193`）经 grill 确认 **vestigial**——main src 零 caller（仅 `SlimSseReducerTest:409-437` 直调），T11 已 reroute 经 `reduceSlimDigest`。处理：加 `@Deprecated` 注释（"T11 后 vestigial；remote watermark 经 reduceSlimDigest"），**不**对称化（伪命题）。真实 remote 写路径 `reduceSlimDigest`（`:329-333`）`remoteMessageId`=last-write-wins / `remoteUpdatedAt`=monotonic-max 的"不对称"经分析**无害**（单调 id 保证陈旧 digest 的 id 必更小 → tuple `needsReconcile` 正确判负）→ **不改合并策略**（YAGNI），仅注释记录该不变量依赖 messageID 单调性。
- `T1-C7`: **测试反转范围扩展**（grill 发现）：除 `SlimapiResyncTest` 外，`SlimapiProbeTest.kt`（:13-20 文档化 needsCatchUp §3 分支顺序，branch 4 `messageID != localAppliedId` + branch 5 `updatedAt > localAppliedTs` = OR-of-two）必须随 Step 8 重写；`SlimSseReducerTest.kt`（:78-79/104-105/157-159/205/238-239 remote-watermark 断言）需 audit——因不改 remoteMessageId 合并策略，多数应仍绿，仅 fetch-trigger tie 用例（若有）随 Step 9 调整。
- `T1-C6`: `./scripts/check.sh` EXIT=0；`SlimapiResyncTest` 全绿。

- [ ] **Step 1: 写 `compareWatermark` 失败测试**

追加到 `SlimapiResyncTest.kt`（用现有 `org.junit.Assert.assertEquals` 风格）：

```kotlin
@Test
fun `compareWatermark lexicographic and null ordering`() {
    // ts 主导
    assertTrue(compareWatermark(200L, "m1", 100L, "m9") > 0)
    assertTrue(compareWatermark(100L, "m9", 200L, "m1") < 0)
    // ts 相等 → id 字典序
    assertTrue(compareWatermark(200L, "m2", 200L, "m1") > 0)
    assertTrue(compareWatermark(200L, "m1", 200L, "m2") < 0)
    assertEquals(0, compareWatermark(200L, "m1", 200L, "m1"))
    // null ts = 最旧
    assertTrue(compareWatermark(null, "m9", 100L, "m1") < 0)
    assertTrue(compareWatermark(100L, "m1", null, "m9") > 0)
    assertEquals(0, compareWatermark(null, "a", null, "b"))
    // ts 相等 + null id = 最旧
    assertTrue(compareWatermark(200L, null, 200L, "m1") < 0)
    assertTrue(compareWatermark(200L, "m1", 200L, null) > 0)
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.vectory.ocdroid.data.repository.SlimapiResyncTest.compareWatermark*"` （或 `./scripts/check.sh` 定向）
Expected: FAIL — `compareWatermark` unresolved reference。

- [ ] **Step 3: 实现 `compareWatermark`**

在 `SlimapiResync.kt` 顶部（`onReconcileSuccess` 之前）新增：

```kotlin
/**
 * Lexicographic compare of two watermark pairs (ts, id).
 * - ts amplifies first; null ts = oldest.
 * - ts equal (both non-null) → id compared lexicographically; null id = oldest.
 *
 * Correctness relies on messageID being lexicographically monotonic by creation
 * (opencode id.ts ascending: msg_<12hex(ts*4096+counter)><random>).
 * Returns <0 if A older, 0 equal, >0 if A newer.
 */
internal fun compareWatermark(tsA: Long?, idA: String?, tsB: Long?, idB: String?): Int {
    if (tsA == null && tsB == null) return 0
    if (tsA == null) return -1
    if (tsB == null) return 1
    if (tsA != tsB) return tsA.compareTo(tsB)
    if (idA == null && idB == null) return 0
    if (idA == null) return -1
    if (idB == null) return 1
    return idA.compareTo(idB)
}
```

- [ ] **Step 4: 运行确认通过**

Run: 同 Step 2
Expected: PASS。

- [ ] **Step 5: 重写 `onReconcileSuccess` tuple 语义（先改对应测试为反转）**

先在 `SlimapiResyncTest.kt` 把现有 "equal ts + different id → retain prior" 用例（`SlimapiResync.kt` 注释指向的 tie / atomic-pair 测试，约 `:257-324`）改为新语义。新增明确用例：

```kotlin
@Test
fun `equal updatedAt larger messageID advances pair`() {
    val prior = SlimSessionState("s", localAppliedUpdatedAt = 200L, localAppliedMessageId = "m1")
    val items = listOf(msg(id = "m2", updated = 200L)) // 同 ts，id 更大
    val out = onReconcileSuccess(prior, items)
    assertEquals(200L, out.localAppliedUpdatedAt)
    assertEquals("m2", out.localAppliedMessageId) // 反转：旧实现 retain m1
}

@Test
fun `equal updatedAt smaller messageID retains prior`() {
    val prior = SlimSessionState("s", localAppliedUpdatedAt = 200L, localAppliedMessageId = "m2")
    val items = listOf(msg(id = "m1", updated = 200L)) // 同 ts，id 更小
    val out = onReconcileSuccess(prior, items)
    assertEquals(200L, out.localAppliedUpdatedAt)
    assertEquals("m2", out.localAppliedMessageId) // 保留 prior
}
```
（`msg(...)` / `SlimSessionState(...)` 构造 helper 沿用该测试文件现有 fixtures。）

Run: 确认这两个测试 FAIL（旧实现 retain prior）。

- [ ] **Step 6: 改 `onReconcileSuccess`（`SlimapiResync.kt:135-195`）实现 tuple 推进**

替换 `latest` 选择与 strict-advance 谓词：

```kotlin
val latest = items
    .filter { (it.info.time?.updated ?: 0L) > 0L }
    .maxWithOrNull(compareBy({ it.info.time!!.updated!! }, { it.info.id ?: "" }))
val observedTs: Long? = latest?.info?.time?.updated
val observedId: String? = latest?.info?.id

val advances = observedTs != null &&
    compareWatermark(observedTs, observedId,
                     state.localAppliedUpdatedAt, state.localAppliedMessageId) > 0
val newTs = if (advances) observedTs!! else state.localAppliedUpdatedAt
val newId = if (advances) (observedId ?: state.localAppliedMessageId)
            else state.localAppliedMessageId
return state.copy(localAppliedUpdatedAt = newTs, localAppliedMessageId = newId, dirty = false)
```
（items 空 → 早 return `state.copy(dirty = false)` 保留不变。）

Run: `./gradlew :app:testDebugUnitTest --tests "*SlimapiResyncTest*"`
Expected: PASS（含重写后的 tie 用例 + 原有 strict-advance / atomic-pair 用例）。

- [ ] **Step 7: `needsReconcile`（`SlimapiResync.kt:67-83`）tuple 化**

把 `remoteId != localId || remoteTs > localTs` 形态的子句替换为：

```kotlin
if (compareWatermark(state.remoteUpdatedAt, state.remoteMessageId,
                     state.localAppliedUpdatedAt, state.localAppliedMessageId) > 0) return true
```
（保留该函数其它早 return 分支，如 dirty/session-missing 等；仅替换 ts/id 比较子句。读原函数确认上下文。）

新增测试 `needsReconcile stale remote id behind local returns false`：remote=(200,m1), local=(200,m2) → false。

- [ ] **Step 8: `needsCatchUp`（`SlimapiProbe.kt:105-118`）tuple 化 + SlimapiProbeTest 重写**

把 OR-of-two（branch 4 `messageID != localAppliedId` + branch 5 `updatedAt > localAppliedTs`）合并替换为 `compareWatermark(probe.updatedAt, probe.messageID, localAppliedUpdatedAt, localAppliedMessageId) > 0`（读原函数确认字段名 + §3 分支顺序，保留前置 short-circuit：probe.ok/empty/messageID==null/localAppliedId==null 不变）。

**重写 `SlimapiProbeTest.kt`**（:13-20 文档化的 §3 分支顺序 + :36-74 各 branch 测试）：把 branch 4/5 的两个独立测试合并为 tuple 用例——`probe(updatedAt=equal, messageID=larger) → true`、`probe(updatedAt=equal, messageID=smaller) → false`（stale，不误 catch-up）。branch 1-3（empty/null/neverApplied）保留。

- [ ] **Step 9: `reduceSlimDigest` trigger（`SlimSseReducer.kt:368-383`）tuple 化 + SlimSseReducerTest audit**

fetch trigger 条件 + messageId-only 防御分支 → 用 `compareWatermark`。读原段确认 `priorRemoteMessageId` / `priorMax` 变量，替换裸 `incomingUpdatedAt > priorMax`。

**audit `SlimSseReducerTest.kt`**（:78-79/104-105/157-159/205/238-239 remote-watermark 断言）：因 **不改** `remoteMessageId` 合并策略（T1-C5），这些断言多数仍绿。仅当存在 fetch-trigger 的同 ts 不同 id 用例时，按新 tuple trigger 语义调整。`:157-159` stale-older-updatedAt no-op 仍成立（tuple 下陈旧 ts 更小 → 不 trigger）。在 task 报告列出 audit 结果。

- [ ] **Step 10: `bumpUpdatedAt`（`SlimSseReducer.kt:193`）vestigial 处理**

grill 已确认 main src 零 caller（grep `\.bumpUpdatedAt(` 仅命中 `SlimSseReducerTest:409-437`）。处理：加 `@Deprecated("T11 后 vestigial；remote watermark 经 reduceSlimDigest 推进")` 注释，**不**改签名/不对称化（伪命题）。`reduceSlimDigest`（`:329-333`）的 `remoteMessageId` last-write-wins / `remoteUpdatedAt` monotonic-max **不动**（T1-C5 已论证无害）。在 task 报告声明 vestigial + 不变量依赖（messageID 单调）。

- [ ] **Step 11: 全量校验**

Run: `./scripts/check.sh`
Expected: EXIT=0，`SlimapiResyncTest` / `SlimSseReducerTest` / `SlimapiProbeTest` 全绿。

- [ ] **Step 12: 记录 diff（不 commit）**

```bash
git rev-parse HEAD          # baseline 945b09f
git diff --stat 945b09f -- app/src/main/java/cn/vectory/ocdroid/data/repository/ app/src/test/java/cn/vectory/ocdroid/data/repository/SlimapiResyncTest.kt
# ocmar 默认不 git add/commit
```

---

## Task 2: P1 — q/p envelope `scope.directories` 消费（修 N==0 误清 stale）

**Files:**
- Modify: `app/src/main/java/cn/vectory/ocdroid/data/model/Slimapi.kt:99,106`（DTO 加 scope）
- Modify: `app/src/main/java/cn/vectory/ocdroid/data/repository/OpenCodeRepository.kt:2434`（`aggregationOutcome` 收 scope）
- Modify: `app/src/main/java/cn/vectory/ocdroid/data/repository/OpenCodeRepository.kt:3368-3388`（`SlimAggregationOutcome` Success/Partial 加 `serverScope`）
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/controller/SessionSyncCoordinator.kt:3187`（`applyAggregationOutcome` scope==0 gating）
- Modify: `OpenCodeRepository.kt:2591,2609`（`coldStartSlimSync` 透传 scope）+ `:2380,2407`（独立 q/p 拉取透传）
- Test: `app/src/test/java/cn/vectory/ocdroid/data/repository/`（q/p repo test）+ `SessionSyncCoordinator` 相关（gating）

**Interfaces:**
- Consumes: 服务端 envelope `scope:{directories:Int}`（503 不含 → null）。
- Produces: `SlimAggregationOutcome.Success.serverScope` / `Partial.serverScope: SlimapiScope?`；`Failure` 不带（无 scope）。

**Acceptance Criteria:**
- `T2-C1`: DTO——`SlimapiQuestionAggregation` / `SlimapiPermissionAggregation` 反序列化含 `scope:{directories:N}` → `scope.directories==N`；envelope 无 scope → `scope==null`；503 不触发反序列化（仍 `HttpException`）。
- `T2-C2`: `aggregationOutcome` 把 envelope `scope` 透传进 `Success(serverScope=…)` / `Partial(serverScope=…)`。
- `T2-C3`: `applyAggregationOutcome` gating——`serverScope.directories==0 && items==[]` → **保留 prior**（不清 stale）；`serverScope.directories>0 && items==[]` → 清 stale；`serverScope==null` → 原逻辑（清）。
- `T2-C4`: `loadPendingQuestionsSlim`（走同一 `applyAggregationOutcome`）自动继承 gating，无需单独改。
- `T2-C5`: `./scripts/check.sh` EXIT=0。

- [ ] **Step 1: 写 DTO + gating 失败测试**

DTO 反序列化测试（`SlimapiDeserializationTest` 或既有 model test）：
```kotlin
@Test fun `question aggregation parses scope`() {
    val json = """{"items":[],"errors":[],"scope":{"directories":3}}"""
    val out = Json.decodeFromString<SlimapiQuestionAggregation>(json)
    assertEquals(3, out.scope?.directories)
}
@Test fun `question aggregation scope null when absent`() {
    val out = Json.decodeFromString<SlimapiQuestionAggregation>("""{"items":[],"errors":[]}""")
    assertNull(out.scope)
}
```
gating 测试（coordinator，`applyAggregationOutcome` 路径）：构造 `Success(items=emptyList(), authoritativeDirectories=null, serverScope=SlimapiScope(0))` → 应用后 prior pendingQuestions **保留**；`serverScope=SlimapiScope(2)` → 清空。

- [ ] **Step 2: 运行确认失败**（`scope` unresolved / gating 仍清空）

- [ ] **Step 3: DTO（`Slimapi.kt`）**

```kotlin
@Serializable
data class SlimapiScope(val directories: Int = 0)
```
`SlimapiQuestionAggregation` / `SlimapiPermissionAggregation` 各加 `val scope: SlimapiScope? = null`。

- [ ] **Step 4: `SlimAggregationOutcome` 加 serverScope（`OpenCodeRepository.kt:3368-3388`）**

```kotlin
data class Success<T>(
    val items: List<T>,
    val authoritativeDirectories: Set<String>?,
    val serverScope: SlimapiScope? = null,
) : SlimAggregationOutcome<T>

data class Partial<T>(
    val items: List<T>,
    val errors: List<SlimapiAggregationError>,
    val authoritativeDirectories: Set<String>,
    val serverScope: SlimapiScope? = null,
) : SlimAggregationOutcome<T>
```
（`Failure` 不变。）

- [ ] **Step 5: `aggregationOutcome`（`:2434`）收 scope**

签名加 `serverScope: SlimapiScope?`，产出时传入 `Success(..., serverScope=serverScope)` / `Partial(..., serverScope=serverScope)`。调用点（`:2591/2609` coldStart、`:2380/2407` 独立拉取）从 envelope `.scope` 取值传入。

- [ ] **Step 6: `applyAggregationOutcome`（`SessionSyncCoordinator.kt:3187`）gating**

在 `Success(scope=null)` 全量替换分支**前**插入：
```kotlin
is Success -> {
    if (outcome.serverScope?.directories == 0) {
        // scope 未就绪（sidecar allowlist 空）→ 保留 prior，不清 stale
        return@applyAggregationOutcome  // 或等价：items = prior，不动 slice
    }
    // …原全量替换 / authoritativeDirectories 增量逻辑
}
```
（读原函数确认 `return` 语义 / `mutateSessionList` 调用结构；保持 Failure/Partial 分支不变。）

- [ ] **Step 7: 运行确认通过** + Step 8: `./scripts/check.sh` EXIT=0 + Step 9: 记录 diff（同 T1 Step 12 模式）。

---

## Task 3: P2a — `/sessions` 列表错误体 code-based（最小深度）

**Files:**
- Modify: `app/src/main/java/cn/vectory/ocdroid/data/repository/OpenCodeRepository.kt:2338-2349`（`parseErrorCode` private→internal）
- Modify: `app/src/main/java/cn/vectory/ocdroid/data/repository/OpenCodeRepository.kt:2361-2368`（`getSlimapiSessions` code-based 失败）
- Test: `app/src/test/java/cn/vectory/ocdroid/data/repository/`（sessions 错误解析）

**Interfaces:**
- Consumes: `SlimapiErrorCodes.UPSTREAM_HTTP_PREFIX` / `UPSTREAM_UNAVAILABLE`（`SlimapiErrorCodes.kt:34-127`，已存在）。
- Produces: `getSlimapiSessions` 失败仍返 `Result<List<Session>>`，但 `Failure` 携带 code（供日志/未来 UX；三态不破坏）。

**Acceptance Criteria:**
- `T3-C1`: `parseErrorCode`（`OpenCodeRepository.kt:2338-2349`）可见性 `private`→`internal`，签名不变；现有 2 caller（`getSlimapiSessionStatusOutcome` / `expandBatchInternal`）零影响。
- `T3-C2`: `getSlimapiSessions`（`:2361-2368`）失败时用 `parseErrorCode` 提 `code` 并**记日志**（既有 logger），**rethrow 原始异常**——grill 确认无 caller 做 `as? HttpException` 特化（唯一命中 `:1587` 是 KDoc），故**不引入新 exception 类型**（带无人消费的 code = 过度设计）。仍返 `Result.failure`（`coldStartSlimSync :2578` `.getOrNull()` 折 null，三态不破坏）。
- `T3-C3`: `./scripts/check.sh` EXIT=0。

- [ ] **Step 1: 写失败测试**

验证 `parseErrorCode` 可见性 + 日志 + 原始异常透传：

```kotlin
@Test fun `sessions list 503 logs upstream_unavailable code and rethrows original`() {
    // mock api 抛 HttpException(503, body={"code":"upstream_unavailable"})
    val result = repo.getSlimapiSessions(...)
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is retrofit2.HttpException) // 未包装
    assertLogged(containing = "upstream_unavailable")                // 沿用既有 logger 断言模式
}
```
（若仓库无 log 断言 helper，T3 内最小新增 capture-log fixture。）

- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: `parseErrorCode` → internal**（`OpenCodeRepository.kt:2338` `private fun`→`internal fun`，1 行改）
- [ ] **Step 4: `getSlimapiSessions`（`:2361`）记 code + rethrow 原始**

```kotlin
suspend fun getSlimapiSessions(...): Result<List<Session>> = runSuspendCatching {
    api.getSlimapiSessions(directories, roots, limit, search)
}.recoverCatching { e ->
    if (e is retrofit2.HttpException) {
        val code = parseErrorCode(e.response())   // internal 复用
        if (code != null) logger.w("slimapi sessions failed: $code")  // 既有 logger
    }
    throw e   // rethrow 原始，不包装
}
```
（`coldStartSlimSync`（`:2578`）仍 `.getOrNull()` 折 null，三态不变；既有 2 caller 零影响。）

- [ ] **Step 5: 运行确认通过** + Step 6: `./scripts/check.sh` EXIT=0 + Step 7: 记录 diff。

---

## Task 4: P2b — q/p directory 客户端规范化去重

**Files:**
- Modify: `app/src/main/java/cn/vectory/ocdroid/util/WorkdirPaths.kt`（新增 `normalizeDirectory`，不改既有 `normalize`）
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/AppCoreOrchestration.kt:159-166`（`computeQuestionFanOutWorkdirs` normalize-dedup）
- Audit: `OpenCodeRepository.kt:1188/1209/2579`（sessions/coldStart directory 透传点）——来源若非规范重复则同处理
- Test: `app/src/test/java/cn/vectory/ocdroid/util/` + `ui/`（fan-out）

**Interfaces:**
- Consumes: 无新依赖。
- Produces: `fun normalizeDirectory(directory: String): String`（`WorkdirPaths.kt`，server-facing，去尾斜杠、根 `/` 保留）。

**Acceptance Criteria:**
- `T4-C1**: `normalizeDirectory("/app/")=="/app"`；`normalizeDirectory("/")=="/"`；`normalizeDirectory("")=="/"`；`normalizeDirectory("/app")=="/app"`（幂等）。
- `T4-C2**: `computeQuestionFanOutWorkdirs` 对 `["/app","/app/","/app","/b"]` → `["/app","/b"]`（normalize 后去重，保序）。
- `T4-C3**: audit sessions/coldStart 透传点：若来源可能含非规范重复，同样过 `normalizeDirectory`+dedup；若来源单规范路径，报告 no-change。
- `T4-C4**: `./scripts/check.sh` EXIT=0。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `normalizeDirectory strips trailing slash preserves root`() {
    assertEquals("/app", normalizeDirectory("/app/"))
    assertEquals("/app", normalizeDirectory("/app"))
    assertEquals("/", normalizeDirectory("/"))
    assertEquals("/", normalizeDirectory(""))
}
@Test fun `fanout dedups after normalize`() {
    val out = computeQuestionFanOutWorkdirs(setOf("/app","/app/"), null, listOf("/app","/b"))
    assertEquals(listOf("/app","/b"), out)
}
```

- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 新增 `normalizeDirectory`（`WorkdirPaths.kt`）**

```kotlin
/** Server-facing normalize: strip trailing slash, preserve root "/". Aligns with
 *  oc-slimapi normalize_directory. (Distinct from [normalize] which is comparison-keying only.) */
fun normalizeDirectory(directory: String): String {
    val t = directory.trim()
    if (t.isEmpty() || t == "/") return "/"
    return t.trimEnd('/')
}
```

- [ ] **Step 4: `computeQuestionFanOutWorkdirs`（`AppCoreOrchestration.kt:159`）normalize-dedup**

```kotlin
internal fun computeQuestionFanOutWorkdirs(
    directorySessionKeys: Set<String>, currentWorkdir: String?, recentWorkdirs: List<String>,
): List<String> =
    (directorySessionKeys + listOfNotNull(currentWorkdir) + recentWorkdirs)
        .filter { it.isNotBlank() }
        .map { normalizeDirectory(it) }
        .distinct()
```

- [ ] **Step 5: audit sessions/coldStart 透传点**

`grep` 确认 `getSlimapiSessions` / `coldStartSlimSync` 的 `directories` 来源；若含 multi-dir 非规范重复风险，在构造点加 `.map(::normalizeDirectory).distinct()`；否则报告 no-change。

- [ ] **Step 6: 运行确认通过** + Step 7: `./scripts/check.sh` EXIT=0 + Step 8: 记录 diff。

---

## Criterion Ownership Matrix

| Criterion ID | Spec requirement | Owner task | Cross-task deps | Verification | Final-only? |
|---|---|---|---|---|---|
| T1-C1 | spec §4.1 `compareWatermark` 字典序+null | T1 | — | `SlimapiResyncTest::compareWatermark*` → PASS | N |
| T1-C2 | spec §4.1 `onReconcileSuccess` tuple 推进 | T1 | — | 重写 tie 测试 → PASS | N |
| T1-C3 | spec §4.1 `needsReconcile` tuple | T1 | T1-C1 | `SlimapiResyncTest::needsReconcile*` → PASS | N |
| T1-C4 | spec §4.1 `needsCatchUp`+`reduceSlimDigest` tuple | T1 | T1-C1 | 单测 + grep 无裸 `ts>`/`id!=` OR-of-two | N |
| T1-C5 | spec §4.1/§6 `bumpUpdatedAt` 对称 | T1 | — | `SlimSseReducerTest` 或 vestigial 报告 | N |
| T1-C6 | 全量校验 | T1 | T1-C1..C5 | `./scripts/check.sh` EXIT=0 | N |
| T2-C1 | spec §4.3 DTO scope 反序列化 | T2 | — | model 反序列化测试 → PASS | N |
| T2-C2 | spec §4.3 `aggregationOutcome` 透传 scope | T2 | T2-C1 | repo 单测 → PASS | N |
| T2-C3 | spec §4.3 gating N==0 不清 stale | T2 | T2-C2 | coordinator gating 测试 → PASS | N |
| T2-C4 | spec §4.3 `loadPendingQuestionsSlim` 继承 | T2 | T2-C3 | 走同一 `applyAggregationOutcome`，代码审查 | Y（跨路径，final 复核） |
| T2-C5 | 全量校验 | T2 | — | `./scripts/check.sh` EXIT=0 | N |
| T3-C1 | spec §4.4 `parseErrorCode` internal | T3 | — | grep 可见性 + 既有 2 caller 测试绿 | N |
| T3-C2 | spec §4.4 sessions code-based 失败 | T3 | T3-C1 | sessions 502/503 code 测试 → PASS | N |
| T3-C3 | 全量校验 | T3 | — | `./scripts/check.sh` EXIT=0 | N |
| T4-C1 | spec §4.5 `normalizeDirectory` | T4 | — | `WorkdirPathsTest` → PASS | N |
| T4-C2 | spec §4.5 fan-out normalize-dedup | T4 | T4-C1 | `computeQuestionFanOutWorkdirs` 测试 → PASS | N |
| T4-C3 | spec §4.5 sessions/coldStart audit | T4 | T4-C1 | grep audit + 报告 | Y（跨文件，final 复核） |
| T4-C4 | 全量校验 | T4 | — | `./scripts/check.sh` EXIT=0 | N |
| F-1 | spec §5.2 `/since` 真过滤联调（无代码改动除非暴露问题） | — | T1 | 模拟器/loopback 联调（handoff §4 endpoint） | Y（final 联调，非单测） |
| F-2 | spec §3.1 P0+P1+P2a+P2b 全交付 | — | T1-T4 | 全 task ✅ + 最终 verifier | Y |

---

## Self-Review

1. **Spec coverage**：spec §4.1→T1（C1-C5）；§4.2→F-1 联调（无代码）；§4.3→T2（C1-C4）；§4.4→T3（C1-C2）；§4.5→T4（C1-C3）。§3.1 四项全覆盖。无遗漏。
2. **Placeholder scan**：无 TBD/TODO；T3 经 grill 简化为 `parseErrorCode`→internal + 记日志 + rethrow 原始（去 `SlimapiHttpException` 过度设计），无占位。
3. **Type consistency**：`compareWatermark(tsA:Long?,idA:String?,tsB:Long?,idB:String?):Int` 在 T1 所有站点一致；`SlimapiScope(directories:Int)` + `serverScope:SlimapiScope?` 在 T2 DTO/outcome/gating 一致；`normalizeDirectory(String):String` 在 T4 一致。
4. **Acceptance observability**：每条 C 都有测试名/command/文件行为 + `T<N>-C<seq>` id；Final-only（T2-C4 / T4-C3 / F-1 / F-2）明确标 Y，留 final 复核。

---

## Grill 修订记录（阶段 3 → plan 回写）

阶段 3 grilling 经代码核实发现 4 项，已回写本 plan：
1. **T1-C5 / Step 10**：`bumpUpdatedAt`（`SlimSseReducer.kt:193`）grep 确认 **vestigial**（main src 零 caller，仅 `SlimSseReducerTest:409-437`）→ 改 @Deprecated 标注，不对称作（伪命题）。真实 remote 写路径 `reduceSlimDigest`（`:329-333`）的 `remoteMessageId` last-write-wins 经论证**无害**（单调 id 保证陈旧 digest id 必更小 → tuple `needsReconcile` 正确判负）→ 不改合并策略。
2. **T1-C7 / Step 8 / Step 9**：测试反转范围扩展——`SlimapiProbeTest`（:13-20 文档化 needsCatchUp §3 分支顺序，branch 4+5 = OR-of-two）**必须重写**；`SlimSseReducerTest`（:78-79/104-105/157-159/205/238-239）remote-watermark 断言 audit（因不改合并策略，多数仍绿）。
3. **T3**：grill 确认无 caller 做 `as? HttpException` 特化（唯一命中 `:1587` 是 KDoc）→ 去掉 `SlimapiHttpException` 新类型（YAGNI），改为 `parseErrorCode`→internal + 记日志 + rethrow 原始。
4. **P0 命门假设核实**：`packages/opencode/src/id/id.ts` 源码确认 messageID（`msg_`+12hex(`ts*4096+counter`)+随机尾，ascending）字典序**严格单调**（含同 ms，counter 自增）→ tuple tie-break 安全可证，无需 monotonicity-agnostic 兜底。

---

## Execution Handoff

Plan 已存 `docs/ocmar/plans/2026-07-20-slimapi-v022-client-adapt.md`。执行模式：**subagent-driven**（fresh implementer per task + reviewer gate + 独立 verifier），按 ocmar-subagent-driven-development。T1 先行（highest risk），T2→T3→T4 顺序（T2/T3 共享 `OpenCodeRepository.kt` 写区，禁止并行；T4 独立文件但排在末尾）。
