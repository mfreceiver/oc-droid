# ocdroid 对 oc-slimapi 三层沟通事项的正式回应

> **状态**：ocdroid 侧正式回应（read-only 调研核实，doc-only，未改任何项目代码）。
> **日期**：2026-07-31。
> **bundle**：B-ocdroid-slimapi-coordination-20260731。
> **回应对象**：oc-slimapi 项目组（经用户转达的三层沟通事项）。
> **共读 SSOT**：`docs/2026-07-31-oc-slimapi-turn-token-contract.md`（双方契约）。
> **ocdroid 侧权威**：`docs/specs/state-machine-architecture-spec.md`（实施后规格，注：§8.1 已滞后于代码，见本回应附录）。
> **核实基准**：commit `22a68ec`（`main` HEAD，含 `e7549e0 fix(authority): close BLK-2 low-turn revival window after baseline clear`）。
> **核实方法**：2 路 explorer 并行代码核实（`file:line` 证据），read-only。

---

## 0. TL;DR（一分钟版）

| 事项 | ocdroid 结论 | 行动项归属 |
|---|---|---|
| 🔴1 C5 header 透传 | **同意**。header 名 `X-Ocdroid-Server-Group-Fp` 合规；值逐字节稳定；应附在**所有**请求（含 SSE），不按 `/slimapi/` 路径 gate。**上线状态：现在实施中**（ocdroid 独立 main 承担，非本回应 scope）。 | ocdroid 实施（独立 main） |
| 🔴2 S2 放宽（连接失败→hole） | **同意**。ocdroid **不依赖**「连接失败 turn 不变」；hole 对 ocdroid 完全不可见，lex 比较天然处理。且 **BLK-2 已于 `e7549e0` 闭合**（spec §8.1 过时，ocdroid 待更新）。建议补 hole 单测（好实践，非阻塞）。V6 改写（send 前 bump → hole）ocdroid 接受。 | ocdroid 补单测（可选）；slimapi 按选项 A 实施 |
| 🔴3 digest stamp scope 反查 | **同意（单映射安全）**。sid 不跨 serverGroupFp 迁移（applyPurge 清旧 scope）；`ScopeKey.slimapiInstanceFp=null` 单实例语义受支持。SSE 订阅经 OkHttp 拦截器链，C5 header 一旦上拦截器**自动覆盖 SSE**，无需单独处理。 | 随 C5 落地自动解决 |
| 🟡 incarnation 策略 A | **无冲突，兼容**。 | 无 |
| 🟡 单实例语义（slimapiInstanceFp=null） | **无冲突，兼容**（两条 ScopeKey 构造分支均 null）。 | 无 |
| 🟡 turn registry 不持久化（restart→0） | **无冲突，兼容**（inc advance 使旧 turn 无关）。 | 无 |
| 🟢 §11 联调 | 列清单，不执行（本 scope 外）。 | 联调阶段 |

> **重要前置发现（附录详述）**：契约 §9 / spec §8.1 描述的 **BLK-2「混用窗口」已由 `e7549e0` 闭合**（新增 `serverRoundHighWater` 水位）。这**增强了** S2 放宽与逐事件混用的安全性——即便 Tier-2 事件清空基线，低 turn 帧也不再复活。此发现对 slimapi 侧策略是**利好**（§9「同 incarnation 不混用」约束的剩余风险被进一步消除）。

---

## 1. 🔴 必须核实（阻塞 Tier-1 生效）

### 1.1 C5 header 透传（`X-Ocdroid-Server-Group-Fp`）

**上线状态**：现在实施中（ocdroid 独立 main 承担，代码落地不在本回应文档 scope）。本节的核实结论作为 C5 实施 main 的交叉验证 + 跨项目技术证据。

#### 1.1.a 请求构造层（在哪构造）

ocdroid 经**共享 OkHttp 客户端工厂 + Retrofit** 构造所有发往服务端的请求；slimapi 与上游共用单一 base URL（`slimHost: Boolean` 标志区分，非双路由表）。

- **HTTP 客户端工厂**：`data/repository/http/OkHttpClientFactory.kt`，`baseBuilder()`（`:164`）组合共享拦截器链（`:181-203`），产出 5 个客户端变体：
  - `restClient()`（`:216`）—— GET（会话列表/消息/文件…），readTimeout 30s，`retryOnConnectionFailure(true)`。
  - `sseClient()`（`:226`）—— SSE 事件流，readTimeout 0（长连接）。
  - `mutationClient()`（`:272`）—— POST 变更（**`prompt_async` / `abort`** / fork / revert / summarize / respondPermission / replyQuestion / rejectQuestion），readTimeout 30s，`retryOnConnectionFailure(false)`（防重复提交）。
  - `commandClient()`（`:323`）—— `POST /session/{id}/command`，readTimeout 300s。
  - `tokenStreamClient()`（`:414`）—— 每会话 SSE token 流，独立拦截器链。
- **Retrofit 包装**：`OpenCodeRepository.buildRetrofit()`（`OpenCodeRepository.kt:531`），baseUrl = `baseUrl.trimEnd('/') + "/"`。
- **关键 POST 入口**：
  - `sendMessage()`（`OpenCodeRepository.kt:1544`）→ `mutationApi.promptAsync(sessionId, request)` → `POST /session/{id}/prompt_async`。
  - `abortSession()`（`:1576`）→ `mutationApi.abortSession(sessionId)` → `POST /session/{id}/abort`。

#### 1.1.b 值来源（`currentServerGroupFp()` 逐字节稳定性）

**逐字节稳定，确定可复用**。算法收敛在两处一致实现：

- **`@Named("currentServerGroupFp")` provider**（`di/ControllerModule.kt:90-98`）：
  ```kotlin
  @Provides @Singleton @Named("currentServerGroupFp")
  fun provideCurrentServerGroupFp(hostProfileStore: HostProfileStore): () -> String = {
      val profile = hostProfileStore.currentProfile()
      profile.serverGroupFp.ifBlank { profile.id }
  }
  ```
  注入为 `() -> String` 惰性 lambda，每次调用重读 `HostProfileStore`。
- **`HostProfile.serverGroupFp` 字段**（`data/repository/HostProfile.kt:49-50`，`String = ""`），规范化 `normalizeGroupFp()`（`:190-191`，`ifBlank → id`）。
- **连接侧等价实现**：`EffectiveConnectionConfig.serverGroupFp`（`service/streaming/EffectiveConnectionConfigResolver.kt:55`），`resolveManual()`（`:133`）与 `resolveProfile()`（`:154`）用同公式 `profile.serverGroupFp.ifBlank { profile.id }`，手动连接回退 `"manual:$baseUrl"`。
- **结论**：对同一逻辑连接，ocdroid 算出的 `serverGroupFp` 逐字节相等（= 契约 §6.1 权威算法）。**由 ocdroid（定义方）透传给 slimapi 是零歧义路径**（slimapi 无需复刻算法，profile 改动自动同步）。

#### 1.1.c header 名建议

**`X-Ocdroid-Server-Group-Fp` 合规**。ocdroid 既有自定义 header 全部遵循 `X-` 前缀 + 驼峰式，常量收敛在 `SlimapiContract`（slimapi 专用）与 `http/HttpHeaders.kt`（通用）：

| 既有 header | 拦截器类 | 文件:行 |
|---|---|---|
| `X-Opencode-Directory` | `DirectoryHeaderInterceptor` | `http/DirectoryHeaderInterceptor.kt:38` |
| `X-Opencode-Skip-Dir` | `HttpHeaders` 常量 | `http/HttpHeaders.kt:25` |
| `X-Slimapi-Version` | `SlimapiVersionInterceptor` | `http/SlimapiVersionInterceptor.kt:48` |
| `X-Client-Name` / `X-Client-Version` / `X-Client-Id` | `ClientIdentityInterceptor` | `http/ClientIdentityInterceptor.kt:74` |
| `Authorization: Basic` | `AuthInterceptor` | `http/AuthInterceptor.kt:38` |

> **命名建议**：若 slimapi 侧希望语义更中立（强调「这是 scope 身份」而非「ocdroid 私有」），可考虑 `X-Server-Group-Fp`。ocdroid 侧无强偏好，**默认采用契约建议的 `X-Ocdroid-Server-Group-Fp`**（明确来源、避免与未来其它客户端 header 冲突）。最终名以 C5 实施 main 落地为准。

#### 1.1.d 实施模式（既定 OkHttp Interceptor 模式）

ocdroid 所有自定义 header 经 **OkHttp `Interceptor`** 注入（`.header(name, value)` 替换语义，非 `addHeader`），统一挂在 `baseBuilder()` 共享链上，**自动覆盖所有 5 个客户端变体（含 SSE 与 token stream）**——无需每端点手动接线。

- **实施位置**：新增拦截器类（如 `ServerGroupFpInterceptor`），加入 `OkHttpClientFactory.baseBuilder()` 链（`:181-203`），并加入 `tokenStreamClient()` 独立链（`:414-441`）。
- **取值 seam（实施注意，供 C5 main 参考）**：当前拦截器层用 `HostSnapshot`（不可变快照，不含 `serverGroupFp`）。两条可行路径：
  1. 给 `HostSnapshot` 加 `serverGroupFp` 字段（拦截器从 `request.tag()` 或 chain 读 snapshot）；或
  2. 拦截器注入 `@Named("currentServerGroupFp") () -> String` 惰性 provider。
  具体选择由 C5 实施 main 决定（非本回应 scope）。

#### 1.1.e 作用范围（关键：不按 `/slimapi/` 路径 gate）

**应附在所有请求（含 SSE），不按 `/slimapi/` 路径 gate。** 这是与 `SlimapiVersionInterceptor` / `ClientIdentityInterceptor` 的**重要区别**：

- 后两者用双门控（`slimHost == true && path.startsWith("/slimapi/")`），因为它们只在 slimapi 自有端点有意义；
- **`X-Ocdroid-Server-Group-Fp` 应无路径门控**，因为 slimapi 是 **catch-all 反向代理**，统一读所有转发请求上的 header（包括透传给上游的 prompt/abort）。若 gate 在 `/slimapi/`，则 `POST /session/{id}/prompt_async`（非 `/slimapi/` 前缀）将不带 header → slimapi 无法在 forward 时读 scope → turn stamp 找不到 `(serverGroupFp, sid)` 计数槽 → fence 静默失效。

**最小必带集**：`prompt_async` + `abort`（forward 增 turn 的两类）。**推荐全集**：所有发往服务端的请求（GET / POST / SSE / token stream），因 slimapi catch-all 统一读、零成本、避免遗漏。OkHttp 拦截器模式天然实现全集。

#### 1.1.f SSE 订阅是否带 header

**C5 一旦上拦截器，SSE 自动覆盖，无需单独处理。**

- **SSE 客户端**：`data/api/SSEClient.kt:215-236` 构建请求（`Accept: text/event-stream` + `Cache-Control: no-cache` + 可选 `Authorization` / `X-Opencode-Directory`）。
- `sseClient()`（`OkHttpClientFactory.kt:226`）走 `baseBuilder()` 共享链 → 拦截器自动注入。
- `tokenStreamClient()`（`:414`）有独立链，但**也挂** `slimapiVersionInterceptor` + `clientIdentityInterceptor` → C5 拦截器需同样挂入此链。
- **scope 反查（🔴3）的 SSE 维度由此一并解决**：slimapi 的 SSE ingest 路径能读到 header → stamp turn 时 scope 关联正确。

#### 1.1.g ocdroid 请求分类（供 slimapi 侧 catch-all 读参考）

slimapi 作为 catch-all 反代会收到 ocdroid 的以下端点流量（同 baseUrl，按路径区分）：

- **POST 变更（mutationClient）**：`prompt_async` / `abort` / `fork` / `revert` / `summarize` / `permissions/{pid}` / `command`。
- **GET（restClient）**：`/session*` / `/global/health` / `/session/status` / `/permission` / `/question` / `/config/providers` / `/agent` / `/command` / `/file*` / `/vcs*` 等。
- **SlimApi 自有端点**：`/slimapi/sessions` / `/slimapi/messages/{sid}` / `/slimapi/messages/{sid}/full/{mid}` / `/slimapi/health`。
- **SSE**：`/global/event`（legacy）/ `/slimapi/events`（slim）/ `/slimapi/sessions/{sid}/stream`（token）。
- **v2**：`/api/model` / `/api/provider`（仅调试）。

> 全部经同一 OkHttp 拦截器链 → C5 拦截器统一覆盖。

---

### 1.2 S2 放宽（连接级失败产生 hole 而非不 increment）

**结论：同意，且比契约预期更强（BLK-2 已闭合）。**

#### 1.2.a ocdroid 是否依赖「连接失败 turn 不变」

**不依赖。** 全文核实（`ui/` 目录 turn/serverRound 消费侧）：

- **lex guard**（`ui/AuthorityReducer.kt:250-257`）只做 `compareTo`（严格单调比较）+ 等值 tie-break（`connectionMonotonicMs`）。无连续性假设、无「期望 turn=N」断言、无 decrement/rollback。
  ```kotlin
  if (op.serverRound != null && prev?.serverRound != null) {
      val cmp = op.serverRound.compareTo(prev.serverRound)
      if (cmp < 0) return cur              // strictly older → DROP
      if (cmp == 0 && op.connectionMonotonicMs < prev.updatedMonotonic) return cur  // tie-break
  }
  ```
- **optimistic-on-success**（`ui/SessionMutationActions.kt:411-432`，dispatch 位于 `.onSuccess { }` 内部 `:341→419`）用 `EntryOrigin.OPTIMISTIC`，`serverRound = null`（`AuthorityReducer.kt:309-313` `keepRound` 逻辑），其 `clientSeq` 与 `ServerRound.turn` 是**永不比较的独立计数空间**（M1，`AuthorityState.kt:89` kdoc 明示）。**「POST 失败不写 optimistic busy」的根因 = dispatch 被 `.onSuccess` 门控**——POST 失败时该块根本不执行，无 claim 写入、无回滚对象（rev-glm 核实确认）。
- **watchdog**（`ui/controller/OptimisticClaimWatchdog.kt:48` `selectStaleClaimsForReconcile`）只读 `OptimisticClaim` 字段，不读 `serverRound`/`turn`。
- **无 `turn.*==` / `turn.*!=` / `serverRound.*==` 断言**（仅 lex `compareTo` 与 null 检查）；**无 `rollback|decrement|turn--|turn-=`**。

**hole 不可见性**：hole 是一个无事件 stamp 的 turn 号。ocdroid 只见到达的事件 → hole 在输入流中不存在 → lex 比较对间隙（如 `1,2,3,5`）天然正确（`5 > 3` 接受）。POST 失败时 ocdroid 不写 optimistic busy（无回滚对象），也不会收到 stamp 该 turn 的事件。

#### 1.2.b 是否补「连接失败=hole」单测

**建议补（好实践，非阻塞）。** 现状：

- 无显式 hole 测试。但 BLK-2 测试隐式覆盖间隙（`app/src/test/.../AuthorityReducerTest.kt:522` 「fresh higher-turn slim frame ACCEPTED after baseline clear」从 turn=7 跳到 turn=9，中间无 8 → 验证间隙被正确接受）。
- **建议**：补一个直接命名「connection-failure hole: turn sequence 1,2,3,5 applied correctly, no expectation of 4」的回归测试，明确文档化 hole 语义。归 ocdroid 侧（可选，不阻塞联调）。

#### 1.2.c V6 验收场景改写接受

**接受**。契约 §11 V6（修订版）= send 前 bump（turn→4）→ `send()` 抛异常 → turn=4 成**空 hole** → ocdroid 完全不可见 → 下次成功 forward turn→5。ocdroid 侧无 V6 代码改动（消费侧已就绪），V6 联调时验证「ocdroid 不感知 hole」即可。

---

### 1.3 digest stamp scope 反查（sid→serverGroupFp 单映射 + SSE header）

**结论：单映射安全；SSE header 随 C5 落地自动覆盖。**

#### 1.3.a sid 是否跨 serverGroupFp 迁移

**不迁移。** host 切换（`serverGroupFp` 变更）触发跨组 purge，旧 scope 的 entries（含 sid 的 `serverRound` / `knownIncarnations` / `coverage`）被清除：

- **`applyPurge`**（`ui/AuthorityReducer.kt:540-550`）：
  ```kotlin
  private fun applyPurge(cur: AuthorityState, op: AuthorityOp.PurgeHost): AuthorityState {
      if (op.preserveServerGroup) return cur     // 同组切换：no-op，保留
      if (cur.bySid.isEmpty() && op.scopeKey !in cur.knownIncarnations && op.scopeKey !in cur.coverage) return cur
      return cur.copy(
          bySid = emptyMap(),                     // 跨组：清全部 sid 条目
          knownIncarnations = cur.knownIncarnations - op.scopeKey,
          coverage = cur.coverage - op.scopeKey,
      )
  }
  ```
- **`preserveServerGroup` 语义**：`true` = 同 `serverGroupFp` 内的 host 切换（如 profile 内 endpoint 变），保留 authority；`false` = 跨组切换，清空。
- **结论**：sid 在生命周期内恒属同一 `serverGroupFp`（跨组即清，不携带 `serverRound`/incarnation 迁移）。**slimapi 的 `sid → serverGroupFp` 单映射假设安全**——一个 sid 不会在两个 group 下产生需要区分的 turn 计数槽。

#### 1.3.b SSE 订阅是否带 serverGroupFp header

**当前不带，但 C5 落地后自动带（推荐路径）。** 详见 1.1.f：

- SSE 经 `sseClient()` / `tokenStreamClient()` 的 OkHttp 拦截器链，C5 拦截器一旦挂入共享链（+ token stream 独立链），SSE 连接请求**自动携带** `X-Ocdroid-Server-Group-Fp`。
- **更干净的方案**：C5 拦截器覆盖 SSE 后，slimapi 的 SSE ingest（GlobalHub）能从连接 header 读 scope → stamp turn 时 `(serverGroupFp, sid)` 关联正确，无需 slimapi 侧额外从 SSE 帧体反查 scope。这是 C5 相对方案 B（slimapi 自算）的结构性优势。
- **过渡期（C5 上线前）**：slimapi 需自行从连接/profile 算 serverGroupFp（方案 B），与 ocdroid §6.1 算法逐字节对齐（契约 §6.3 硬要求）。C5 上线后切方案 A，slimapi 直读 header。

---

## 2. 🟡 声明确认（slimapi 侧声明，ocdroid 确认无冲突）

### 2.1 incarnation 策略 A（`persisted_last + 1`）

**无冲突，兼容。** ocdroid 消费侧（inc advance 处理）对 slimapi 的 bump 策略无假设——只要求「跨 restart 严格单调不减」（契约 §5.2），策略 A（每次启动 +1）满足。

- **inc advance 逻辑**（`ui/AuthorityReducer.kt:371-388`）：`op.serverRound.incarnation > highWater` → bump 该 scope high-water + 重置该 scope entries 的 `serverRound = null`（**per-scope，非全局**；`serverRoundHighWater` 保留，见附录）。
- **策略 A 兼容性**：restart → inc `7→8` → ocdroid 见 `inc=8 > known=7` → 重置该 scope 基线 → 新事件正常建立基线（UI 不卡 busy，契约 §11 V1）。

### 2.2 单实例语义（`ScopeKey.slimapiInstanceFp = null`）

**无冲突，兼容。** null 分支受支持且为当前实际行为。

- **`ScopeKey` 定义**（`data/state/AuthorityState.kt:134-138`）：`slimapiInstanceFp: String? = null`（可选）。
- **构造点**（`ui/controller/sse/SseDispatchHost.kt:188-206` `applyStatusViaAuthority`）：**两条分支均省略 `slimapiInstanceFp`**（默认 null）：
  ```kotlin
  val (scopeKey, ...) = if (eventIdentity != null) {
      Triple(ScopeKey(serverGroupFp = eventIdentity.serverGroupFp, endpointFp = eventIdentity.endpointFp), ...)
  } else {
      Triple(ScopeKey(serverGroupFp = serverGroupFp(), endpointFp = ""), ...)
  }
  ```
- **结论**：单实例语义（`slimapiInstanceFp=null`）是 ocdroid 当前实际使用的模式，incarnation high-water 按 `(serverGroupFp, endpointFp)` 维度独立。未来若 slimapi 部署多实例且 ocdroid 能区分，`ScopeKey` 已预留可选字段，无需改 ocdroid 数据模型。

### 2.3 turn registry 不持久化（restart → turn 归零，inc bump 保证正确性）

**无冲突，兼容。** 契约 §7.3 明示「两种都正确」，ocdroid 侧由 inc advance 兜底。

- **restart → inc bump → ocdroid 重置该 scope serverRound 基线**（`AuthorityReducer.kt:371-388`）→ 旧 turn（无论 slimapi 侧是否归零）自然过期。
- **turn registry 持久化与否对 ocdroid 透明**：ocdroid 不假设 turn 跨 incarnation 连续，只要求「同 incarnation 内 per-(serverGroupFp,sid) 单调不减」（契约 §4.6 不变量 1）。slimapi restart 后 turn 归零 + inc bump → ocdroid 见新 incarnation 的 turn 从小值开始，基线已重置，正确。

---

## 3. 🟢 联调确认清单（§11 验收，本次只列不执行）

以下场景本回应**仅列清单**，执行属联调阶段（本 scope 外）。ocdroid 侧消费端已就绪（`0d572d2` + `e7549e0`），slimapi 发字段即可联调。

| # | 场景 | ocdroid 侧就绪状态 | 备注 |
|---|---|---|---|
| V1 | restart 不冻结 | ✅ 测试 `incarnation advance resets prior turns serverRound`（`AuthorityReducerTest.kt:418`） | inc advance → scope reset |
| V5 | abort fencing | ✅ lex DROP（`AuthorityReducer.kt:250`）+ 确认门 | abort increment → 终态 turn 更高 |
| V6（改写） | 连接级失败成 hole | ✅ 消费侧天然处理（无依赖）；建议补显式 hole 单测 | send 前 bump，hole 不可见 |
| V7 | 非 2xx 产生 hole | ✅ 同 V6（lex 处理间隙） | |
| V11 | serverGroupFp 一致性 | ⏳ 待 C5 落地（方案 A）或 slimapi 方案 B 对齐 | C5 上线后自动对齐 |
| 混用窗口规避 | C5 上线走 restart 伴随 inc bump | ✅ inc advance 重置基线；且 BLK-2 已闭合（附录） | §9 约束的剩余风险进一步消除 |
| serverGroupFp 一致性校验 | 双方对同一连接算出相同 fp | ⏳ 联调时验证（C5 上线后零歧义） | |

---

## 4. 附录：BLK-2 已闭合（spec §8.1 过时，ocdroid 待更新）

**这是本回应核实过程中发现的对 slimapi 侧利好，单独成节以供双方知晓。**

### 4.1 现状

契约 §9 与 `docs/specs/state-machine-architecture-spec.md` §8.1 描述的 **BLK-2「混用窗口」已由 commit `e7549e0` 闭合**（`main` HEAD `22a68ec` 已含）。spec §8.1 文本（第 376-386 行）仍将其列为「惰性 / P1 backlog」，**已滞后于代码**。

### 4.2 闭合机制（`serverRoundHighWater`）

新增 **per-sid 持久水位** `SessionEntry.serverRoundHighWater: ServerRound?`（`data/state/AuthorityState.kt:75-83`），记录该 sid 曾接受过的字典序最大 `(incarnation, turn)`，**在基线清空后依然保留**：

- **水位推进**（`ui/AuthorityReducer.kt:343-354`）：Tier-1 帧折叠向前（lex max，永不回退）；Tier-2/REST/IDLE（`op.serverRound == null`）保留水位不变。
- **BLK-2 守卫**（`ui/AuthorityReducer.kt:259-284`）：
  ```kotlin
  if (op.serverRound != null && prev != null && prev.serverRound == null &&
      prev.serverRoundHighWater != null &&
      op.serverRound < prev.serverRoundHighWater
  ) {
      return cur   // 基线清空后的低 turn 帧 → DROP
  }
  ```
- **水位存活于三类基线清空**：
  - REST `ApplySnapshot`（`AuthorityReducer.kt:491-500`）：`serverRoundHighWater = priorEntry?.serverRoundHighWater`。
  - Legacy SSE busy（`keepRound=null`）：水位经 `prevHw` 保留。
  - Incarnation-advance scope reset（`:376-382`）：`.copy(serverRound = null)` 不触及水位。
- **测试覆盖**：6 个 BLK-2 测试（`AuthorityReducerTest.kt:465-679`），显式覆盖 REST 清基线、incarnation-advance 清基线、高 turn 接受、等 turn 不过 fence、cold start；水位存活由 `.copy(serverRound=null)` 不触及字段保证（适用三类清基线源，legacy SSE busy 由同机制覆盖，测试未单独列）。

### 4.3 对 slimapi 侧的含义

- **§9「同 incarnation 不混用」约束的剩余风险被进一步消除**：即便 slimapi（在升级窗口或异常路径下）发出 Tier-2 事件清空了基线，随后到达的低 turn Tier-1 帧**不再复活**（被水位 DROP）。
- **不改变 slimapi 侧实施义务**：slimapi 仍应遵循 §9（同 incarnation 一致带 turn、升级走 restart/inc bump）——这是契约要求，BLK-2 闭合只是 ocdroid 侧的纵深防御。
- **S2 hole 放宽更安全**：hole 不会在基线清空窗口被误用复活。

### 4.4 ocdroid 侧行动项

**ocdroid 侧待更新 spec §8.1**（反映 BLK-2 已闭合 + 引用 `serverRoundHighWater` 机制）。属 ocdroid 内部文档维护，**非本回应 scope，不阻塞联调**。本回应仅记录发现，提示 ocdroid 维护者跟进。

---

## 5. 行动项汇总

| # | 行动项 | 归属 | 阻塞联调？ |
|---|---|---|---|
| A1 | C5 实施 `X-Ocdroid-Server-Group-Fp` 拦截器（无路径 gate，覆盖全客户端含 SSE/token stream） | ocdroid 实施 main（**现在实施中**） | 是（Tier-1 前置） |
| A2 | slimapi 按 §10.1 S1–S9 实施（发字段 + send 前 bump + incarnation 持久化 + ingest 快照 + serverGroupFp 对齐） | slimapi | 是 |
| A3 | C5 上线前，slimapi 用方案 B（自算 §6.1）过渡；C5 上线后切方案 A（读 header） | slimapi | 否（过渡） |
| A4 | ocdroid 补「连接失败=hole」显式单测（好实践） | ocdroid（可选） | 否 |
| A5 | ocdroid 更新 spec §8.1（BLK-2 已闭合） | ocdroid 文档维护 | 否 |
| A6 | 联调执行 §11 场景（V1/V5/V6改写/V7/V11/混用窗口） | 双方（联调阶段） | — |

---

## 6. 结束语

ocdroid 侧对 slimapi 三层沟通事项的核实结论：**🔴三项全部同意**（C5 现在实施中、S2 放宽安全、scope 单映射安全），**🟡三项声明全部兼容无冲突**，**🟢联调清单已列待执行**。

核实过程中额外发现 **BLK-2 已闭合**（`e7549e0`），这对 slimapi 侧是利好（§9 混用窗口剩余风险进一步消除，S2 hole 放宽更安全）。ocdroid 侧 spec 文档滞后问题为内部维护项，不阻塞联调。

下一步：slimapi 完成 S1–S9 + C5 上线 → 双方按 §11 联调。

---

*回应文档结束。本文件供用户直接转达 oc-slimapi 项目组。*
