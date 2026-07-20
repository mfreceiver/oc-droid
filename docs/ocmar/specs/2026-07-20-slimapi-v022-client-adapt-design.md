# oc-slimapi v0.2.2 客户端适配 — 设计 spec

| | |
|---|---|
| **日期** | 2026-07-20 |
| **slug** | `slimapi-v022-client-adapt` |
| **基线** | `origin/main`（HEAD `945b09f`，working tree 干净） |
| **owner** | `ocmar-slimapi-v022-client-adapt` |
| **风险定级** | **High-risk**（多文件、触动 SSE reconcile 状态机、watermark 是 correctness-critical 单调状态） |
| **输入** | oc-slimapi v0.2.2 handoff（`docs/ocmar/reports/2026-07-20-v0.2.2-ocdroid-handoff.md`）+ ocdroid 侧 3 缺口核对 + opencode v1.18.3 源码核实 |
| **wire 影响** | 无（纯客户端改动；服务端 wire 仍 `1`，已部署 v0.2.2） |

---

## 1. 需求来源

oc-slimapi v0.2.1/v0.2.2 修复了 2 个 pre-existing 真 bug + ratify 了 ocdroid 提出的 3 个契约遗留缺口（详见 handoff）。部分修复要求 ocdroid 客户端配合，否则要么不生效、要么踩正确性坑。本 spec 覆盖这些客户端适配改动。

服务端关键变更（handoff 摘要）：
1. **`/since/{ts}` 时间过滤此前是 no-op**（opencode v1.18.3 message schema 无 `info.time.updated`），现已读 `info.time.updated or info.time.created`，**真过滤生效**。
2. **tie-break 定义为 `(updatedAt, messageID)` 二元组字典序**（对齐上游 `(time_created DESC, id DESC)`）。
3. **q/p envelope 加性字段 `scope:{directories:N}`**（N=0 → scope 未就绪；N>0 && items=[] → 权威空）。
4. **`/slimapi/sessions` 列表失败路径对齐 §7**：4xx→502 `upstream_http_N`、5xx/网络→503 `upstream_unavailable`、body 统一 `{"code":...}`。
5. q/p 显式 directory 规范化去重；`invalid_directory_count` 守卫改按规范化后 fan-out 数判定。

---

## 2. 现状核验结论（3 条 explorer 侦察 + opencode 源码核实）

### 2.1 reconcile / watermark 核心（`SlimapiResync.kt` / `SlimSseReducer.kt`）
- **当前 strict-advance 是纯标量**：`onReconcileSuccess`（`SlimapiResync.kt:135-195`）只在 `observedTs > priorTs` 时推进 `(ts, id)` pair（pair 取自 `maxByOrNull { time.updated }` 单条 item）；ts 相等则 pair 不动。
- **watermark 存储**：`SlimSessionState`（`SlimSseReducer.kt:67-140`）4 个可空标量字段（`remoteMessageId` / `remoteUpdatedAt` / `localAppliedMessageId` / `localAppliedUpdatedAt`），存于 `SlimSseState.sessions: MutableMap`。
- **`/since` 调用链**：`reconcileSessionLocked`（`SessionSyncCoordinator.kt:2416-2428`）→ `localAppliedUpdatedAt != null` 时 `repo.getSlimapiMessagesSince(sid, since)` → `bumpSlimBookmarkFromItems`（`OpenCodeRepository.kt:2731-2746`）→ `onReconcileSuccess`。
- **已知陷阱**：`SlimSseReducer.bumpUpdatedAt`（`:192-200`）只写 `remoteUpdatedAt` 不写 `remoteMessageId`（不对称）；`remoteMessageId` 由 `reduceSlimDigest`（`:331`）单独 last-write-wins。tuple 语义下必须一致。
- **测试将反转**：`SlimapiResyncTest.kt:151-414` 多条 "equal ts + different id → retain prior" 断言（`:257-324` tie/atomic-pair）在 tuple 字典序语义下**行为反转**（id 更大时应推进），须重写。

### 2.2 messageID 单调性 — **已源码核实（命门假设成立）**
opencode ID 生成器 `packages/opencode/src/id/id.ts`：
- 格式 `msg_` + 12 hex（编码 `Date.now()*4096 + counter`）+ 14 base62 随机尾；消息用 `ascending` 方向。
- `counter` 同毫秒内自增（`:54-58`）→ 12-hex 前缀**按创建顺序严格递增**，固定宽度 → **整个 ID 字典序严格单调递增**（随机尾永不参与比较，前缀不并列）。
- **结论**：slimapi 处方 `(updatedAt, messageID)` tuple 字典序 tie-break **安全可证**，包括同毫秒内。原先担忧的"同 ms id 乱序→死循环"风险**不存在**。无需 monotonicity-agnostic 的兜底策略（YAGNI）。

### 2.3 cold-start / q/p scope（`OpenCodeRepository.kt` / `SessionSyncCoordinator.kt`）
- **三态 empty-vs-fail 已实现**：sessions/messages 用 `List<T>?`（null=fail；empty=权威空）；q/p 用 sealed `SlimAggregationOutcome`（`Failure`/`Success(items, authoritativeDirectories?)`/`Partial`）。
- **但存在真实 correctness bug**：q/p DTO（`Slimapi.kt:99/106`）**无 `scope` 字段**，服务端新增 `scope:{directories:N}` 被 kotlinx 静默丢弃 → `N==0`（scope 未就绪）和 `N>0 && items==[]`（权威空）都解析成 `items=[]` → `aggregationOutcome`（`OpenCodeRepository.kt:2434`）折成 `Success(empty)` → `applyAggregationOutcome`（`SessionSyncCoordinator.kt:3187`，scope=null 全量替换分支）→ **误清本地 stale pending**。触发窗口：sidecar 启动早于 opencode（allowlist 空）。

### 2.4 sessions 列表错误体 / q/p directory（`OpenCodeRepository.kt` / `AppCoreOrchestration.kt`）
- **sessions 列表错误解析 = 粗判**：`getSlimapiSessions`（`OpenCodeApi.kt:286-293`）返回裸 `List<Session>`，非 2xx → `HttpException` → `runSuspendCatching` → `Result.failure`，**不读 body `code`、不分 HTTP 状态**。与 `getSlimapiSessionStatusOutcome`（`:1451-1531`，404/400/502/503 逐状态 + `parseErrorCode`）和 `expandBatchInternal`（`:2204-2266`）**不一致**。
- `parseErrorCode` 是 `OpenCodeRepository` 的 `private fun`（`:2338-2349`），仅 2 处调用（status + messages-full-batch），sessions/q/p 未复用。`SlimapiErrorCodes` 常量齐全。
- **ocdroid 确实发 q/p 显式 `?directory=` repeated**：`computeQuestionFanOutWorkdirs`（`AppCoreOrchestration.kt:159-166`）仅 `.distinct()`，**不做 trailing-slash 规范化**（`WorkdirPaths.normalize` 文档明确"仅 comparison keying，原始串透传 server API"）。slim q 调用点（`SessionSyncCoordinator.kt:1837`）传 list；slim p（`SessionListActions.kt:1022`）传 null。→ 服务端 v0.2.2 规范化去重**确实影响** ocdroid 发出的请求，但服务端更宽松（`/app`+`/app/` 合并、不再误触 `invalid_directory_count`），**无破坏**。
- routeToken 与 directory 来源解耦，P2 directory 变更不影响 routeToken 路径。

---

## 3. 范围

### 3.1 在范围内
| 项 | 级别 | 必要性 |
|---|---|---|
| **P0** tie-break watermark 升级为 `(updatedAt, messageID)` tuple 字典序 + `/since` 真过滤联调 | High | **必做**（服务端 `/since` 已真过滤；不升级则等时间戳不同 id 消息触发 spurious reconcile 循环） |
| **P1** q/p `scope.directories` 消费（修 N==0 误清 stale bug） | Medium | **建议做**（窄窗口 correctness bug） |
| **P2a** `/sessions` 列表错误解析对齐 §7（code-based） | Medium | **做**（一致性 / 错误粒度；**最小深度**：失败带 code，三态不破坏） |
| **P2b** q/p (+ sessions/coldStart) directory 客户端规范化去重 | Low | **做**（与 v0.2.2 服务端 fan-out 计数对齐；联调验证一并做） |

### 3.2 仅核对
（用户确认全做后无项；原 P2b 已移入 §3.1。）

### 3.3 不在范围内（YAGNI / 延后）
- 不改服务端（wire 已定，v0.2.2 已部署）。
- 不做 monotonicity-agnostic 兜底（id.ts 已证单调，无需）。
- 不做 `/since/0` → cursor drain 的客户端切换（handoff 仅"推荐"，ocdroid 现有 cursor drain façade `fetchSlimInitialWindowBounded` 已是无 watermark 路径，不变）。
- 不动 `message.appended` forward-compat（v1.18.3 不发射，hub 保留）。
- 不重构 `parseErrorCode` 为共享 helper（除非 P2a 自然要求；YAGNI）。

---

## 4. 设计

### 4.1 P0 — tie-break tuple 字典序（核心）

**语义**：watermark 推进从标量 `observedTs > priorTs` 升级为二元组字典序 `(observedTs, observedId) > (priorTs, priorId)`（ts strict `>` 优先；ts 相等则 id strict `>`，由 id.ts 单调性保证正确）。

**改动点**（均经 explorer-1 核实行号）：
1. `SlimapiResync.onReconcileSuccess`（`:135-195`）：
   - `latest` 选择：`maxWithOrNull(compareBy({ updated }, { id }))`（当前 `maxByOrNull { updated }` 不 tie-break）。
   - strict-advance 谓词：`(observedTs, observedId) > (priorTs, priorId)` 字典序；满足时两字段一起搬到 latest。
   - items 空 → 仍只清 dirty（不变）。
2. `SlimapiResync.needsReconcile`（`:67-83`）：现 OR-of-two（`remoteId != localId || remoteTs > localTs`）→ tuple 字典序（`remoteTs > localTs || (remoteTs == localTs && remoteId != localId && remoteId > localId)`，等价于 `(remoteTs, remoteId) > (localTs, localId)`）。注意保留 `null` 边界（localTs==null 时仍需 reconcile）。
3. `SlimapiProbe.needsCatchUp`（`:105-118`）：同 needsReconcile 的 tuple 化。
4. `SlimSseReducer.reduceSlimDigest` fetch trigger（`:368-383`）：`incomingUpdatedAt > priorMax` 的触发条件 + messageId-only 防御分支 → tuple 字典序。
5. `SlimSseReducer.bumpUpdatedAt`（`:192-200`）：消除不对称——tuple 语义下 `remoteMessageId` 与 `remoteUpdatedAt` 必须**一起**写（或显式确认 `:331` 的 last-write-wins 路径在所有 bump 路径上都覆盖；倾向直接修 `bumpUpdatedAt` 同写两字段，单一真相源）。

**测试改写**：`SlimapiResyncTest.kt:151-414` 的 tie / atomic-pair / "equal ts + different id → retain prior" 断言**行为反转**——重写为 "equal ts + larger id → advance to (ts, largerId)"、"equal ts + smaller id → retain prior"。新增同 ms 多消息（id 单调）收敛测试。

### 4.2 P0 — `/since` 真过滤联调（验证，非大改）
- 客户端 `/since` 调用本身不变（`localAppliedUpdatedAt` 作 ts）。服务端从 no-op 变真过滤后，返回**子集**（`>= ts`），客户端 messageID 去重兜底继续 work。
- **验证要点**：用真实 watermark 拉 `/since/{ts}`，确认返回子集而非全量；构造同 `updatedAt` 不同 `messageID` 边界，验证 tuple 推进无残留、无死循环。
- 无代码改动除非联调暴露问题。

### 4.3 P1 — q/p `scope` 消费

**改动点**：
1. **DTO**（`data/model/Slimapi.kt:99/106`）：`SlimapiQuestionAggregation` / `SlimapiPermissionAggregation` 各加 `val scope: SlimapiScope? = null`；新增 `@Serializable data class SlimapiScope(val directories: Int)`。503 不含 scope → 反序列化为 null（与服务端契约一致）。
2. **解析 + 透传**（`OpenCodeRepository.kt:2434` `aggregationOutcome`）：签名加 `scope` 参数；产出 `Success(items, authoritativeDirectories, serverScope)`（`SlimAggregationOutcome` 扩一个 `serverScope` 字段）。`requestedDirectories` 仍作 client-side authoritative 来源；`serverScope` 作 gating 信号。
3. **gating**（`SessionSyncCoordinator.applyAggregationOutcome :3187`）：在 scope=null 全量替换分支前加判断——`if (serverScope?.directories == 0) → 保留 prior（等同 Failure 语义，不清 stale）`；否则原逻辑。`loadPendingQuestionsSlim`（`:1820`，走同一 `applyAggregationOutcome`）自动继承。
4. `coldStartSlimSync` 内两处直调（`:2591/2609`）透传 scope 到 `aggregationOutcome`。

**向后兼容**：旧字段全保留；scope=null（旧服务端 / 503）走原逻辑，不破坏。

### 4.4 P2a — `/sessions` 列表错误解析对齐

**改动点**：`getSlimapiSessions`（`OpenCodeRepository.kt:2361-2368`）从裸 `runSuspendCatching` 升级为按 HTTP 状态 + `parseErrorCode` 路由（仿 `getSlimapiSessionStatusOutcome`），至少区分：
- 502 `upstream_http_N` / 503 `upstream_unavailable` → 可重试失败（带 code）。
- 其它 → 通用失败。
**最小实现**：把 `parseErrorCode` 提升为 `internal fun`（或 `SlimapiErrorCodes` 伴生 helper）供 list 路径复用；list 返回类型维持 `Result<List<Session>>`（失败仍折 null/Failure，但带 code 供日志/未来 UX）。
**注意**：`coldStartSlimSync`（`:2578`）把 sessions 失败折成 `null`（三态保留）；改后仍折 null，但底层解析带 code。不破坏三态契约。

### 4.5 P2b — directory 客户端规范化去重

**目标**：与 v0.2.2 服务端 fan-out 计数对齐（服务端现按 `normalize_directory` 去尾斜杠、根 `/` 保留后去重 + 守卫判定）。

**改动点**：
1. **主战场 `computeQuestionFanOutWorkdirs`**（`AppCoreOrchestration.kt:159-166`）：`.distinct()` → 先 `normalizeDirectory` 再 `.distinct()`，消除 `/app`+`/app/` 双 fan-out / 双 routeToken。
2. **normalize 语义**：去尾斜杠、根 `/` 保留（对齐服务端 `normalize_directory`：`s.rstrip("/") or "/"`）。**复用或新增** 一个与 `WorkdirPaths.normalize`（现仅 comparison keying）区分的 server-facing normalize；倾向在 q/p fan-out 处就地用新 helper，不改 `WorkdirPaths.normalize` 既有契约。
3. **审计其它 multi-dir 构造点**：`coldStartSlimSync`（`:2579`）/ sessions list（`:1188/1209`）透传上层 `directories`——若来源已是单规范路径则不动；若可能含非规范重复，同样过 normalize-dedup。
4. **联调验证**（并入本 task）：`?directory=/app&directory=/app/` → 服务端现按 1 dir fan-out；客户端 normalize 后也发 1 dir，双方一致；不误触 `invalid_directory_count`（33 raw 去重 ≤32 → 200）。

**向后兼容**：normalize 是幂等收紧（少发重复 dir），服务端行为不破坏；routeToken directory 由 token 反查，不受客户端 fan-out normalize 影响（explorer-3 确认解耦）。

## 5. 成功标准 + 测试方法（TDD）

### 5.1 P0
- `onReconcileSuccess`：同 ts 不同 id，id 更大 → 推进到 (ts, largerId)；id 更小 → 保留 prior。同 ts 多消息收敛（应用全集后 watermark = (ts, maxId)）。
- `needsReconcile` / `needsCatchUp`：tuple 字典序判定，含 null 边界。
- `bumpUpdatedAt`：ts 推进时 remoteMessageId 同写（对称）。
- 回归：`/since` 真过滤下，watermark 推进不残留、不死循环（属性测试或场景测试）。

### 5.2 P1
- DTO：服务端 envelope 含 `scope:{directories:0}` → 解析为 `SlimapiScope(0)`；不含 → null。
- gating：`scope.directories==0 && items==[]` → **不清** stale（保留 prior）；`scope.directories>0 && items==[]` → 清 stale；`scope==null` → 原逻辑（清）。
- 503 不含 scope → null → 不影响 Failure 分支。

### 5.3 P2a
- `/sessions` upstream 502/503 → 失败带对应 `code`（`upstream_http_N` / `upstream_unavailable`），不再是裸 `{"detail":...}`。
- 三态不破坏（失败仍折 null，empty 仍替换）。

### 5.4 验证命令
- 全量：`./scripts/check.sh`（编译 + 单测，EXIT=0）。
- 定向：`.venv`/gradle 对应 module test。
- **设备纪律**：UI/插桩测试仅模拟器（`./scripts/emulator.sh`）；单测为主，本轮改动纯数据/repo 层，以单测验证。

---

## 6. 风险与对策

| 风险 | 对策 |
|---|---|
| P0 tuple 语义在多入口（digest/probe/reconcile）不一致 → 部分路径漏改 → 死循环 | 集中提炼一个 `compareWatermark(tsA,idA,tsB,idB): Int` 纯函数，所有 4 处复用；单测覆盖每入口 |
| `bumpUpdatedAt` 不对称历史陷阱 | 改为同写两字段；测试 `bumpUpdatedAt` 对称性 |
| P0 测试反转被误当回归失败 | 重写测试时标注语义变更（commit message + 测试注释引用本 spec §4.1） |
| P1 scope==null 旧服务端回退 | scope 可空 + null 走原逻辑；测试覆盖 null/0/>0 三态 |
| P2a 提升 `parseErrorCode` 可见性波及其它调用点 | 仅 `private`→`internal`，签名不变；现有 2 调用点零影响 |
| 联调缺真实 v0.2.2 环境 | handoff §4 给了 loopback `127.0.0.1:4097` + 远程 mTLS `opencode.vectory.cn:14097`（已部署 v0.2.2）；模拟器对接 |

---

## 7. 决策（🔑 已确认）

1. **范围**：✅ **P0 + P1 + P2a + P2b 全做**（用户确认）。
2. **P2a 深度**：✅ **最小——带 code 的失败**（list 失败仍折 null/Failure，三态不破坏；底层 `parseErrorCode` 提升为 internal 复用，失败带 `upstream_http_N`/`upstream_unavailable` code）。
3. **P1 scope 字段形状**：✅ **`scope:{directories:Int}`**（只 directories，YAGNI）。

---

## 8. 后续

- spec 确认后 → `writing-plans` 产实施 plan（task 拆分 + TDD 步骤 + 验证命令）。
- 开发模式：subagent-driven（fresh implementer + reviewer + 独立 verifier + ocmar-state ledger）。
- 不 commit（除非用户显式要求）；不发版（release 留用户触发）。
