# ocdroid SSE 建联失败排查 — 问题 / 发现 / 方案 / 评审纪要

> **状态**：根因层定型（编排者代码核实 + rev-bgpt / rev-opus 双轮门控评审收敛）
> **日期**：2026-07-25
> **定位**：token stream 自取消循环的排查基线 + 已核实证据 + 评审结论 + 行动计划。行号均为排查时快照，实施时以最新代码为准。
> **结论一句话**：**不是网络/服务端问题，是 ocdroid 客户端的 SSE 自取消循环。** token stream 连上后 ~190ms 被客户端自身 cancel（`eventSource.cancel()` → OkHttp `SocketException: Socket closed`），周而复始。

---

## 0. 结论

token stream 连上后 ~190ms 被客户端自身 cancel，循环往复。**根因层**：`TokenStreamCoordinator` 的单槽 supersede 设计 + 两个 `open()` 入口未与 REST load 的 single-flight 去重对齐。**外层驱动**（高可信候选）：digest 驱动反复 load → open，`open()` 未被去重。控制面 `attemptId=46` 是诊断计数器膨胀，**不是** 46 次失败重启。

置信度（编排者 + 两轮评审收敛后）：

| 结论 | 置信度 |
|---|---|
| 非 网络/服务端 | 极高（客户端侧）；服务端 curl 证据来自 oc-slimapi 仓库、本仓库不可独立核实 |
| 非 看门狗 / 非 readTimeout | 极高 |
| token stream = 本地协程 cancel（"Socket closed" = 本端自关） | 极高 |
| 单槽 supersede **能**制造 self-cancel | 极高 |
| 190ms 自取消由重复 `open()` supersede 驱动 | 高（~80-85%，含时序指纹） |
| 本次现场故障确实由重复 `open()` 触发 | 中高（~80-85%，缺 call-site 事件日志） |
| `attemptId=46` 是计数器而非 46 次失败 | 高 |
| `server.connected{}` 应使控制面 Ready | 高 |
| stale 命中 4 条件之一（未实测哪条） | 中 |
| 外层驱动 = digest 风暴（候选①） | 中高（静态链确认，现场 cadence 待诊断） |

---

## 1. 环境与拓扑

```
ocdroid (OkHttp mTLS)
  → frp 公网入口 59.36.97.245:14097  (opencode.vectory.cn → CNAME frp-leg.com)
  → 本机 stunnel mTLS :14097
  → oc-slimapi :4097 (sidecar, 健康未降级)
  → opencode :4096
```

两个 SSE 端点（分属不同所有者）：
- **控制面** `GET /slimapi/events` — 归 `SessionStreamingService` / `ServiceSseConnectionOwner`，经 `StreamingServiceLauncher` 启动（`attemptId` 属此路）。
- **token stream** `GET /slimapi/sessions/{sid}/stream` — 归 `TokenStreamCoordinator`（`@UiApplicationScope`），opt-in。

---

## 2. 现象

### 2.1 ocdroid Logcat（按时间正排）
```
53.869  TokenStream/WARN   failure sid=ses_… Socket closed
53.932  Sync/WARN          loadMessages failed: stale or not-ready slim repository incarnation
53.969  TokenStream/INFO   connecting sid=ses_… directory=/home/mar/opencode_wd
54.019  StreamingSvcLauncher  ensureStarted: launch begin (attemptId=46, identity=ConnectionIdentity(epoch=0, serverGroupFp=A, normalizedWorkdir=/home/mar/opencode_wd, endpointFp=https://opencode.vectory.cn:14097))
54.019  StreamingSvcLauncher  ensureStarted: Stage-1 ack received (attemptId=46, elapsedMs=0)
54.067  TokenStream/INFO   connected sid=ses_…
54.180  OpenCodeRepository  catalog: 8 provider(s), 42 model(s)        ← /config/providers
54.258  TokenStream/WARN   failure sid=ses_… Socket closed            ← 连上后 ~191ms
54.376  TokenStream/INFO   connecting …
54.522  TokenStream/INFO   connected …
```
- **循环 A**：token stream 连上 → ~190ms → Socket closed → 重连。
- **循环 B**：控制面 `attemptId` 爬到 46。
- **附带**：`loadMessages failed: stale or not-ready slim repository incarnation`。

### 2.2 服务端视角（oc-slimapi access.jsonl，本仓库不可独立核实）
每条 token stream 请求 **HTTP 200**，存活仅 3–5s，下行仅 92~427 字节（只有握手帧）后由**客户端断开**。源地址均为 `127.0.0.1`（stunnel 正常终结 mTLS 转发）。

---

## 3. 已排除项（附代码证据，已逐条核实）

| 假设 | 结论 | 证据（已核实 file:line） |
|---|---|---|
| sidecar 实现/头不对 | 排除 | oc-slimapi 侧 `routes/token_stream.py`（仓库外） |
| stunnel mTLS / frp / 流式 gzip | 排除 | curl 矩阵（oc-slimapi 侧证据，本仓库不可独立核实） |
| token 客户端 readTimeout | 排除 | `data/repository/http/OkHttpClientFactory.kt:387` `.readTimeout(0,…)` |
| 心跳看门狗触发 190ms 断开 | 排除 | `ui/controller/sse/TokenStreamCoordinator.kt:917` `TOKEN_WATCHDOG_MS=45_000`；`:486-488` 对任意帧 reset `lastFrameAt`；`:699-701` 仅 `elapsed≥45s` 触发；日志无 "watchdog timeout" |
| "Socket closed" = 远端断开 | 否 | `data/api/TokenStreamClient.kt:144-155` `awaitClose{ eventSource.cancel() }`；`:127-130` onFailure 打 `t.message`；远端净关走 `onClosed` "closed by server"（`:116`） |
| 控制面首帧不 Ready | 否 | `service/streaming/ServiceSseConnectionOwner.kt:754-757` `!readiness.isCompleted → Ready`，无类型过滤；event-typed 帧解析 `data/api/SSEClient.kt:386-390` |

### 3.1 "Socket closed" 的语义（关键）
`TokenStreamClient.onFailure` 打印 `t.message`。`java.net.SocketException: Socket closed` 表示**本端自己关了 socket**——对应 `awaitClose { eventSource.cancel() }`。远端断开会走 `onClosed`（"closed by server"）或 `onFailure` 带 "unexpected end of stream"。所以日志里的 Socket closed = **收集协程被 cancel**（典型来自 supersede）。

### 3.2 时序指纹（决定性，首轮评审补强）
logcat 两次 `failure → connecting` 间隔为 100ms / 118ms，**精确落在 `OPEN_DEBOUNCE_MS=100`（`:922`）窗口**，绝非 `INITIAL_BACKOFF_MS=1000`（`:925`）的 reconnect 退避、也非 `RETRY_AFTER_503_MS=5000`（`:931`）的 503 重试。且 `scheduleReconnect`（`:834` 必打日志、间隔 ≥1000ms）与 503 日志在窗口内**双双缺席**。→ 本窗口每次重连都是**新 `open()` 驱动**，不是 reconnect/503 驱动。

---

## 4. 根因（机制已代码核实）

### 4.1 机制：重复 `open()` 互相 supersede
`TokenStreamCoordinator` 的 collector 是**全局单槽** `currentStreamJob: AtomicReference<Job?>`。`launchStreamLifecycle()`（`ui/controller/sse/TokenStreamCoordinator.kt:890-901`）每次都：
```kotlin
val job = scope.launch(start = CoroutineStart.LAZY) { block() }
val prior = currentStreamJob.getAndSet(job)
prior?.cancel(CancellationException("superseded by $reason sid=$sid"))
job.start()
```
而 token stream 有**两个 open 入口**，二者结构都是"先 `launchLoadMessages(...)`（有 `isLoadingMessages` 去重），**再** `tokenStreamCoordinator.open(...)`"：
- `ui/ChatViewModel.kt:128-152`（load :128-140；open :145-152）
- `ui/AppCoreOrchestration.kt:840-877`（load :841-858；open :869-876）

REST load 的去重（`ui/MessageActions.kt:75-79` `if (isLoadingMessages) return`）**只挡第二个 REST load，挡不住第二个 caller 继续执行后面的 `open()`**。第二个 `open(B)` → `launchStreamLifecycle` → cancel `job-A` → `awaitClose{eventSource.cancel()}` → "Socket closed"。与"连上 ~190ms 即断、服务端只见客户端主动断开"完全吻合。

**设计意图印证**：两处注释自承"max-1 + debounce makes any dual-open **harmless**"（`ChatViewModel.kt:144`、`AppCoreOrchestration.kt:865`）。设计者认为双开无害（指无重叠 collector），但"无害" ≠ "无 self-cancel"——max-1 恰恰保证 prior 被 cancel。

### 4.2 已有 100ms debounce（首轮报告遗漏，已补）
`OPEN_DEBOUNCE_MS=100L`（`:922`）；`:286-293` debounce 内若 `currentSid.get() != capturedSid` 则跳过 `runStream`。所以 <100ms 的 burst 会被吞掉（不连真 socket），只有 >100ms 间隔的 open 才会真连上又被取消。这把循环间隔锁定在 ~digest 量级（250ms），**强化候选①**而非削弱。

---

## 5. 外层驱动（候选①链路已代码确认，置信度中高）

完整链路存在且 `open()` 处无去重：
```
session.digest → SlimSseHandler.handleSessionDigest
 → SlimSessionReconciler.prepareSessionDigest（SlimSessionReconciler.kt:442）
 → applySlimDigest 返回 SlimFetchMessages?（仅 updatedAt 严格推进，SlimSseReducer.kt:280-311）
 → SseSyncDecision.ReloadSession → ControllerEffect.LoadMessages（SessionSyncCoordinator.kt:856-860）
 → AppCore.kt:530 → loadMessagesForEffect（AppCoreOrchestration.kt:840）
 → launchLoadMessages（被 isLoadingMessages 去重）+ open()（❌ 未去重，:869-876）
```
关键：**即使 `launchLoadMessages` 因 `isLoadingMessages=true` 短路，其后紧跟的 `open()` 仍执行** → supersede → cancel → "Socket closed"。~250ms digest 间隔与 ~190ms connect→close 吻合。

**残留经验性未知**：观测窗口是否处于"生成活跃、updatedAt 持续推进"（reducer 只在 remote 推进时发 `SlimFetchMessages`；idle 无推进 → 无 fetch → 无 open → 不循环）。这解释了为何稳定 session（不生成时）不复现。

---

## 6. 误导项（症状，非根因）

- **`attemptId=46` 是计数器膨胀**：`service/StreamingOwnershipGate.kt:127-181` 中**所有 4 分支都 `++attemptIdCounter`**，包括"Ready-same-identity 直接复用"（`launchRequired=false`）；`service/StreamingServiceLauncher.kt:137` 又无条件打 `launch begin (attemptId=…)`。所以 46 ≠ 46 次启动失败。
- **`stale or not-ready` 命中 4 条件之一**（`data/repository/SlimSseStateMachine.kt:100-103`）：`!token.issuedReady` / `!slimIncarnationReady` / `token.marker !== slimCommitMarker` / `!isTokenEpochCurrent(token)`，未实测命中哪条。
- **`epoch=0` 与 slim stale 独立**：日志 `identity=ConnectionIdentity(epoch=0,…)` 是 ConnectionIdentity epoch。`isTokenEpochCurrent` 用 slim 的 `tokenEpochs`（`:163-167`），**非** identity epoch；`beginSlimReconfigure()`（`:146-153`）旋转 marker + 清 `tokenEpochs` + 置 `slimIncarnationReady=false`，但**不碰 identity epoch**。故 `stale or not-ready` 与 `epoch=0` 并行不悖——`epoch=0` 不能否决 slim 侧问题。

---

## 7. 附带发现（已重新定性）

`ui/controller/HostProfileController.kt:673-678`（`configureRepositoryForProfileRaw`）调 `repository.configure(...)` **未传 `slim = profile.slim`**，而 `data/repository/OpenCodeRepository.kt:621` 的 `slim` 默认 `false`。

但 `OpenCodeRepository.kt:617-620` 注释明写这是**已知未完成的迁移**（"默认 false……待 EffectiveConnectionConfig / 上游 controller 接入 slim 字段后端到端生效"），**非新发现 bug**。修复（补 `slim = profile.slim`）仍正确，是补齐既定迁移最后一环。**不是 190ms self-cancel 的充分解释**，应独立提交。

---

## 8. 候选修复（供评审，非定案）

1. **`TokenStreamCoordinator.open()` 对相同连接幂等**：当 `currentSid==sid && currentDirectory==directory && currentStreamJob 仍 active/pending` 时不 supersede。**必须限定 `reason="open"`**，不误伤 `reason="reconnect"`（走 `scheduleReconnect`，需能 supersede）。与现有 100ms debounce **互补**（debounce 处理 <100ms burst，幂等处理 >100ms steady-state churn）。—— 直接消除 self-cancel，不依赖调用方配合。
2. **`HostProfileController.kt:673-678` 补 `slim = profile.slim`**（独立项）。
3. **stale 四条件加诊断后再谈恢复逻辑**：当前 `StaleSlimCommitException` 在 `MessageActions.kt:419-428` 仅 log + emit error，无 retry；`launchLoadMessagesWithRetry`（`:464-482`）仅固定延迟单次。未见诊断前不要盲目加 retry。
4. **`StreamingOwnershipGate` 在 Ready-same-identity 复用时不分配新 attemptId**（可观测性，非根因）。

---

## 9. 编排者代码核实纪要

逐条对照主干代码（`app/src/main/java/cn/vectory/ocdroid/`）核实，结论：报告客户端侧几乎所有 file:line 论断属实。

| 报告论断 | 核实 | 证据 |
|---|---|---|
| 4.1 单槽 supersede | ✅ | `TokenStreamCoordinator.kt:890-901` |
| 4.1 两个 `open()` 入口 | ✅ | `ChatViewModel.kt:145-152`、`AppCoreOrchestration.kt:869-876` |
| 4.1 REST load 去重挡不到 `open()` | ✅ | `MessageActions.kt:75-79`；`open()` 在 `loadMessagesForEffect` 末尾无条件执行 |
| 4.1 "open B 关 A"是设计意图 | ✅ | 两处注释"max-1 + debounce makes any dual-open harmless" |
| 3.1 `Socket closed` = 本端 cancel | ✅ | `TokenStreamClient.kt:144-155` + `:127-130` |
| 3 看门狗排除 | ✅ | `:917`、`:486-488`、`:699-701` |
| 3 `readTimeout(0)` | ✅ | `OkHttpClientFactory.kt:387` |
| 4.2 `attemptId` 计数器 | ✅ | `StreamingOwnershipGate.kt:127-181` 全分支 `++`；`StreamingServiceLauncher.kt:137` 无条件打日志 |
| 4.3 控制面首帧即 Ready | ✅ | `ServiceSseConnectionOwner.kt:754-757`；`SSEClient.kt:386-390` |
| 4.4 `epoch=0` 与 slim stale 独立 | ✅ | `SlimSseStateMachine.kt:163-167`（tokenEpochs）；`:146-153`（beginSlimReconfigure） |
| 4.5 slim 透传缺失 | ✅ | `HostProfileController.kt:673-678` + `OpenCodeRepository.kt:621`（已知迁移缺口） |
| 4.6 无自动 retry loop | ✅ | `MessageActions.kt:419-428`、`:464-482` |

**编排者额外确认**：候选①链路 `digest → SlimFetchMessages → LoadMessages → open()` 静态成立且 `open()` 未去重（`SessionSyncCoordinator.kt:856-860`、`AppCore.kt:530`、`SlimSseReducer.kt:280-311`），可将驱动置信度从「中」上调到「中高」。

---

## 10. 双轮门控评审纪要

### 10.1 Stage-1：rev-bgpt（gpt-5.6-sol）

**认同**：单槽 supersede 具备制造该症状的能力；两个 open 入口未与 REST load single-flight 对齐是真实缺口；100ms debounce 无法防 >100ms 重复 open；watchdog/readTimeout 排除可信；attemptId/stale/epoch 重新定性合理。

**质疑/反对**：
1. **事件级归因未完成**：代码只证明"若有第二次 open 会产生该现象"，没证明"现象发生时一定有第二次 open"。Socket closed 也可能来自 `close(sid)` / session-switch / host-switch / scope cancel / old-job late callback。应降级为"高可信候选根因"。
2. **~250ms 时间吻合是相关性非因果**：digest 处理异步、effect bus dispatch、open 100ms delay、isLoadingMessages 清除时机都会引入延迟。
3. **候选①"已代码确认"表述过强**：应拆成"静态存在"vs"现场发生"两层。
4. **Fix① 简单实现有 TOCTOU**：多 AtomicReference 撕裂读 + `Job.isActive` 不足以做 owner 判定；"判定 duplicate + 发布新意图"必须同一原子临界区。
5. **`currentStreamJob` 非严格 owner token**：`onStreamFailure`（`:730-745`）、`scheduleReconnect`（`:774-805`/`:827-850`）未按 generation 验证 current owner。
6. **reason 语义边界未定义**：open 遇同 sid reconnect backoff 时应复用 backoff / 提升为立即 open / 保持 reconnect？
7. **EventSource factory 未真正排除**：`TokenStreamClient.kt:141-142` 每次新建，但 factory/client 是否共享未核实。

**改进建议**：统一不可变连接状态对象（sid/dir/gen/job/kind/phase）+ 单 CAS 或 Mutex/actor 串行化；不用 `Job.isActive` 作唯一幂等条件；给 open/job/transport 分配可追踪 ID 并落结构化日志；幂等测试覆盖 4 类（active+same / pending+same / active+diff-dir / same-sid-reconnect-backoff+open）。

**置信度（节选）**：重复 open 能解释 ~190ms 断：85%；本次现场确实由重复 open 触发：65-75%；Fix① 简单 if 安全：30%；服务端/网络完全排除：45-60%。

### 10.2 Stage-2：rev-opus（claude-opus，终审）

**终审判定**：原方案方向正确、Fix① 是对的最小修复；bgpt 的归因严谨性质疑成立但置信度下调过头（漏看 logcat 已有的时序指纹），其 Fix①"统一原子状态对象 + owner token"升级属过度工程（忽略生产 scope 是 `Main.immediate` 单线程）。**可进入实现：Fix① + 诊断日志一并落地。**

**对根因置信度的裁决（修正 bgpt，上调到 ~80-85%）**——三个判别器：
1. **100ms 去重指纹 ≠ 1000ms 退避指纹**：logcat 两次 failure→connecting 间隔 100ms/118ms → 只可能是 open() 去重窗口，绝非 ≥1000ms reconnect 退避。
2. **`scheduleReconnect` 一定会打日志（`:834`）且间隔 ≥1000ms**——窗口内缺席 → 排除 reconnect/503 路径，锁定 open() 路径。
3. **"Socket closed" 精确指向客户端自关**：服务端净关走 `onClosed` "closed by server"（`:115-120`）；观测到 `onFailure` + "Socket closed"（`:122-134`）是 `eventSource.cancel()`（`:154`）触发的本地 SocketException → 削弱"服务端未排除 45-60%"的权重。

**对 Fix① 的裁决（bgpt 升级过度，最小 if 在本项目安全）**：核实注入的 `@UiApplicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`（`di/UiApplicationScope.kt:50`），两个 open call-site 都在此 scope。单线程受限 dispatcher 上：`launchStreamLifecycle` 的 `getAndSet+cancel` 已是原子单槽（LAZY-start 保证 set 先于 body）；open() 里"读 current 状态 + 决定跳过"只要同步无挂起点，无 TOCTOU 窗口；late-callback 由 transport `closed` guard + 结构化并发兜底（superseded job 在挂起点抛 `CancellationException`，被 `:741-742/802-804/846-847` 单独 rethrow）。

**最小安全形态**（在 `open()` 入口、`sseDisabled()` 之后、`currentSid.set` 之前，同步执行）：
```kotlin
fun open(sid: String, directory: String? = null) {
    if (sid.isBlank()) return
    if (sseDisabled()) { /* … */; return }
    // Fix①: 同 sid+同 dir+活跃 lifecycle → 幂等跳过（Main.immediate 单线程，同步无 TOCTOU）
    val cur = currentStreamJob.get()
    if (currentSid.get() == sid &&
        currentDirectory.get() == directory &&
        cur != null && cur.isActive) {
        DebugLog.i(TAG, "open($sid) idempotent — active lifecycle already owns sid+dir; skip supersede")
        return
    }
    currentSid.set(sid)
    // …原逻辑
}
```
注：此 if 会同时吞掉"同 sid reconnect-backoff 中的 open"（reconnect job 也在 `currentStreamJob` 且 active）——可接受（reconnect 会连同一 sid）；"open 撞 backoff 是否提升为立即连"属 UX 细化，低优先级搁置。

**对 bgpt 评审本身的评价**：
- 成立且有价值：静态 vs 现场两层拆分、要求事件级归因日志、识别两个 open 入口 vs load-only 去重。
- 过度：Fix① 按多线程 dispatcher 评了，忽略 `Main.immediate`；漏看时序指纹；服务端未排除权重偏高。
- 唯一合理残值：简单 if 的安全性**依赖"open() 永远在 Main.immediate 上调用"**——已核实两个 call-site 均在 `appScope`，但若未来新增 off-main call-site，该 if 会退化为不安全 → Fix① 处应加注释或 `assert` 锁定该前提。

---

## 11. 整合后的最终行动项（三档）

**立即可做（同 commit，低风险可逆，机制已确认）：**
1. 落 Fix① 最小幂等 guard（上面伪代码）+ dispatcher 前提注释/断言（bgpt 残值）。
2. 加诊断日志（最小集）：`open()` entry（含 `source` 标签）；`launchStreamLifecycle` 的 `prior?.isActive`（冒烟枪）；`loadMessagesForEffect:875` 与 `ChatViewModel:151` 各传常量 `source`。
3. `./scripts/check.sh` + 幂等单测（active+same-sid+same-dir → skip；同 sid 不同 dir → 不 skip 走 supersede；reconnect-backoff 中 open 同 sid → skip 且不破坏后续连接）。

**加诊断后再做：**
4. 真机/模拟器 repro logcat 确认 supersede `priorActive=true` 且 `source=effect-load` → 根因定案（85% → 确认）。
5. 若确属 digest 风暴：评估 digest→open 之间加 stream-presence 判定（Fix① 幂等后收益递减，视 repro 数据定）。
6. open-during-backoff 是否提升为立即连（reason 语义细化）。

**暂不做：**
7. bgpt 的"统一原子连接状态对象 + owner token + Mutex/actor"——过度工程，除非未来 coordinator 迁多线程 dispatcher。
8. 服务端 subscriber-replacement 深挖——"Socket closed"已指向客户端自关，降为低概率兜底。
9. stale / attemptId / epoch 相关——已正确重新定性为误导项，无需动。

---

## 12. 关键证据索引（file:line）

| 主题 | 位置 |
|---|---|
| 单槽 supersede | `ui/controller/sse/TokenStreamCoordinator.kt:890-901` |
| `open()` 入口 + 设计意图 | `:262-312`；"open B 关 A" `:248-261` |
| 看门狗常量 / reset | `:913-918`、`:486-488`、`:699-703` |
| debounce 常量 | `:920-922`（`OPEN_DEBOUNCE_MS=100`） |
| reconnect / 503 常量 | `:925`（`INITIAL_BACKOFF_MS=1000`）、`:931`（`RETRY_AFTER_503_MS=5000`） |
| 两个 open 调用点 | `ui/ChatViewModel.kt:145-152`、`ui/AppCoreOrchestration.kt:869-876` |
| REST load 去重（保护不到 open） | `ui/MessageActions.kt:68-80` |
| stale 异常定义 | `data/repository/OpenCodeRepository.kt:368-369` |
| stale 4 条件 | `data/repository/SlimSseStateMachine.kt:100-103` |
| `beginSlimReconfigure` 副作用 | `:146-153` |
| `isTokenEpochCurrent`（tokenEpochs） | `:163-167` |
| digest→fetch 决策 | `data/repository/SlimSseReducer.kt:280-311` |
| ReloadSession→LoadMessages | `ui/controller/SessionSyncCoordinator.kt:856-860` |
| `attemptId` 计数策略 | `service/StreamingOwnershipGate.kt:127-181` |
| `launch begin` 日志 | `service/StreamingServiceLauncher.kt:130-138` |
| 控制面首帧 Ready | `service/streaming/ServiceSseConnectionOwner.kt:651-764`（Ready `:754-757`） |
| event-typed 帧解析 | `data/api/SSEClient.kt:382-390` |
| slim 透传缺失 | `ui/controller/HostProfileController.kt:672-678` + `data/repository/OpenCodeRepository.kt:621` |
| token 客户端 readTimeout(0) | `data/repository/http/OkHttpClientFactory.kt:387` |
| `Socket closed` 来源 | `data/api/TokenStreamClient.kt:115-134, 144-155` |
| 单线程 scope（Fix① 安全性前提） | `di/UiApplicationScope.kt:50` |
