# Bug：新会话消息永久"加载中" / `stale or not-ready slim repository incarnation`

> 版本：v0.16.0（HEAD `6e10fb0`，代码与发版一致）
> 严重度：P0（新建会话不可用，仅杀进程可恢复）
> 类型：客户端状态机自愈缺失

---

## 1. 现象

1. **打开"刚发了第一条消息"的新会话报错**，logcat 反复出现：
   ```
   Sync/WARN: loadMessages failed: stale or not-ready slim repository incarnation
   Sync/DEBUG: loadMessages P0-7 retry check: isFailure=true resetLimit=true sseLive=true cause=<StaleSlimCommitException>
   Sync/DEBUG: loadMessages stale token (attempt 1/2), retrying in 500ms
   Sync/DEBUG: loadMessages stale token (attempt 2/2), retrying in 500ms
   ```
   重试 2 次（共 ~1s）仍失败，之后放弃。

2. **所有消息显示为"加载中"**。消息**流式加载期间能显示内容**，但**流式一结束就立刻变回"加载中"**。

3. **杀进程是唯一恢复手段**：杀进程重开后，**之前产生的旧会话能正常显示**；但**重开后新建的会话又会一直卡"加载中"，直到下一次杀进程**。

4. **"服务器弹窗硬刷新"无效**：硬刷新后连接显示正常（绿点），但消息仍不加载。

5. 服务器/sidecar 实际正常：日志里 `/slimapi/questions` 返回 200、SSE 正常连上（`sseLive=true`）。

---

## 2. 根因（已确认的机制）

**`slimIncarnationReady` 是一个没有自愈机制的粘性锁存位（sticky latch）。**

- 它由 `beginSlimReconfigure()` 置 `false`（`SlimSseStateMachine.kt:168`），**只有** `configure()` 走到末尾的 `completeSlimReconfigure()`（`OpenCodeRepository.kt:967`）才能置回 `true`。
- `configure()` 是 fail-forward：中途任一步抛错就不 complete，readiness 保持 `false`（`OpenCodeRepository.kt:1006-1013`）。
- 一旦 readiness=false 且后续没有一次**成功**的 `configure()`，它就**整个进程寿命永久卡死**——只有杀进程（重建 repository，初始值 `true`，见 `SlimSseStateMachine.kt:57`）能恢复。

### 两个症状同源

| 症状 | 机制 |
|---|---|
| 新会话 `loadMessages` 报 stale | `getMessagesPaged` → `drainAndCommitAuthoritative` → slim token 守卫。`isTokenCurrentLocked` 要求 `slimIncarnationReady && token.issuedReady && marker && epoch && identity && bundle`（`SlimSseStateMachine.kt:100-106`）。readiness=false → 抛 `StaleSlimCommitException`（`SlimSseStateMachine.kt:150-154` / `:660-662`）。`MessageActions.kt:146` 的重试只覆盖 ~1s 瞬态窗口，cover 不了持久卡死。 |
| 流式可见、结束变加载中 | 流式内容走 SSE token-stream 直写 `streamingPartTexts`，**不经 slim token 守卫** → 可见；流式结束要经 slim 权威提交（`commitFull200` / `drainAndCommitAuthoritative`）落盘，同样被 readiness=false 拒绝 → 提交落空 → 清掉流式 overlay 后 revert 回"加载中"。 |

---

## 3. 为什么"硬刷新无效、杀进程才行"（已确认）

`coldStartReconnect`（= 服务器弹窗硬刷新，见 `ConnectionHealthProbe.kt:749` 注释）走 `testConnectionWithEngine` → `engine.bootstrap()` → `performAttempt`：

```kotlin
// ConnectionBootstrapEngine.kt:112-128
val expected = identityStore.currentIdentity.value
val matchingIdentity = expected?.serverGroupFp == key.serverGroupFp &&
    expected.normalizedWorkdir == key.workdir && expected.endpointFp == key.url
if (configuredKey != key || !matchingIdentity) {   // ← host 没变 + identity 匹配 → 跳过 configure
    repository.configure(...)                       // ← 全局唯一会 completeSlimReconfigure 的地方之一
    configuredKey = key
}
// …checkHealth → 服务器正常 → 返回 Success
```

**`performAttempt` 在 host key 与 identity 都匹配时跳过 `configure`**，直接 `checkHealth`（服务器正常）→ 返回 `Success`。`testConnectionWithEngine`（`ConnectionHealthProbe.kt:619-660`）拿到 Success 就写 `isConnected=true, Connected` 并返回——**全程没调用 configure → readiness 永不复位**。`configuredKey` 是 engine 私有字段，与 readiness 脱钩后，之后每次 bootstrap 都跳过 configure。

结果：硬刷新报告"已连接/绿点"，但消息仍不加载。杀进程重建 `configuredKey=null` + readiness=true，才恢复。

---

## 4. 初始 brick 来源（任一即可；自愈缺失后都变永久）

代码全量检索 `beginSlimReconfigure()` 调用点（main 源码共 4 处），均不在"新建会话/发消息"路径上，所以**会话创建本身不直接 brick**。brick 来自某次 reconfigure 事件：

1. **`resetLocalDataAndResync` 显式 begin 不 complete**（`HostProfileController.kt:849`）。代码注释 845-846 自承：
   > "It also never calls configure() (the ticket is a dummy) and emits ColdStartReconnect"
   
   它 begin 后丢掉 ticket，只发 `ColdStartReconnect`，完全依赖上面（已断掉的）自愈链。

2. **`configure()` 抛错**（fail-forward 设计）。一次瞬态 SSL/URL/cert/`requireClientBundle()` 构造失败即置 false；且此时 `configuredKey` 未更新、identity 未 null（`performAttempt:119` 调 configure 前不 null identity），之后 engine 永远 `configuredKey==key && matchingIdentity` → 跳过 configure → 永不复位。

---

## 5. 关于"旧会话正常 / 新会话卡住"的说明（需 1 个实验确认）

杀进程重开后，旧会话能显示 = 当时 readiness 还是 `true`（重开初值），旧会话成功 slim-fetch 并写入 chat slice + `sessionWindowCache`（`SessionSwitcher.kt:138`）。之后某次 reconfigure 事件把 readiness 翻 false，**此后所有需要 fresh-fetch 的会话都失败**；已加载的旧会话靠内存 slice/缓存继续显示。

**待确认实验**（用于 100% 坐实"全局 brick"而非"per-session"）：
- 复现：新建会话卡住后，**回去强制刷新一个之前已打开过的旧会话**。
  - 若旧会话也变"加载中" → 确认是全局 readiness brick（与本诊断一致）。
  - 若旧会话仍显示 → 是从 `sessionWindowCache` 命中（`SessionSwitcher.kt:177`），底层 fresh-fetch 同样失败，只是 UI 没触发——仍与本诊断一致。

> 注：日志已直接证明新会话的 `loadMessages` 抛 `StaleSlimCommitException`，机制层面已确认。本实验只为进一步缩小"触发时机"。

---

## 6. 修复方案（按优先级）

### Fix 1（主修复，必做）：bootstrap engine 自愈 readiness

让 engine 在 readiness 掉线时**强制走一次 configure**，恢复自愈。修后**所有 brick 原因都能在下一次 coldStart/硬刷新时自愈**，trigger 变得无关紧要。

**改动点：**

a) `SlimSseStateMachine.kt` 暴露只读 readiness（新增）：
```kotlin
fun isReady(): Boolean = synchronized(slimStateLock) { slimIncarnationReady }
```

b) `OpenCodeRepository.kt` 转发（新增 public）：
```kotlin
fun isSlimIncarnationReady(): Boolean = slimStateMachine.isReady()
```

c) `ConnectionBootstrapEngine.kt:112` 跳过条件加上 readiness 判断：
```kotlin
if (configuredKey != key || !matchingIdentity || !repository.isSlimIncarnationReady()) {
    repository.configure(
        key.url, key.username, key.password,
        hostPort = hostPortFromUrl(key.url),
        clientCert = clientCert,
        slim = key.slim,
    )
    configuredKey = key
}
```

### Fix 2（防御纵深，建议做）：`resetLocalDataAndResync` 不再用 dummy ticket

本地数据 wipe 不改 host/transport，不应让 host-switch 原语把 incarnation 永久 brick。二选一：

- **2a（推荐）**：`HostProfileController.kt:849` 把 begin 返回的 ticket 接住，本地 wipe 完成后用**同一 ticket** 调 `completeSlimReconfigure(ticket)` 重新置 ready。slimSseState 已被 begin 清空（watermarks 会从后续 digest 重建），readiness 即时恢复。
- **2b**：`resetLocalDataAndResync` 改用不清 readiness 的更窄 purge（类似 `clearWatermarksForReconnect`），不动 incarnation。

> Fix 1 落地后，2 的价值是减少不必要的 brick 抖动（每次 resetLocalDataAndResync 都短暂失效所有 in-flight slim 操作）。两者不冲突，建议都做。

### Fix 3（可观测性，建议做）：埋点定位触发源 + 防回归

在以下位置加 `DebugLog`，确认真实触发路径并便于回归监控：
- `SlimSseStateMachine.beginSlimReconfigure()`（:164）——记录调用栈/原因。
- `SlimSseStateMachine.completeSlimReconfigure()`（:242）——记录成功。
- `OpenCodeRepository.configure()` 的 catch（:1006）——记录抛错原因（`requireClientBundle` / SSL / `SupersededSlimReconfigureException` 等）。
- `ConnectionBootstrapEngine.performAttempt` 跳过 configure 分支——记录跳过原因与当前 `isSlimIncarnationReady()`。

---

## 7. 不建议的修法

- **加大 `MessageActions.kt:146` 重试次数/时长**：治标不治本，readiness 持久卡死时无限重试只会卡 UI；根因是缺自愈。
- **在 `loadMessages` 失败时自行触发 configure**：把加载路径耦合到 reconfigure，破坏关注点分离；Fix 1 已从正确的层级（bootstrap）解决。

---

## 8. 验证步骤

1. **单测**（必做，`./scripts/check.sh`）：
   - 新增：构造 readiness=false 场景，调 `engine.bootstrap()`（或 `performAttempt`），断言**触发了 `repository.configure`** 且 `repository.isSlimIncarnationReady() == true`。
   - 新增：`resetLocalDataAndResync` 后（Fix 2a）断言 readiness 恢复 true。
   - 回归：现有 `OpenCodeRepositorySlimapiEndpointsTest`（C-D3 rev-3 系列）、`SlimSseStateMachineRaceTest`、`SessionSyncCoordinatorSlimTest` 全绿——这些是高密度回归面，改动需保证不破坏 begin/complete/ticket-ownership 不变量。

2. **手测**（模拟器，遵守 `docs/specs/emulator-debug.md` 占用纪律）：
   - 复现原 bug 路径：连 slim host → 新建会话发消息 → 确认不再卡"加载中"。
   - 验证自愈：人为制造一次 configure 抛错（如临时断 sidecar 再恢复）→ 硬刷新 → 确认消息恢复加载。
   - 验证旧会话：杀进程重开 → 开旧会话正常 → 新建会话也正常。

3. **回归面提示**：该状态机经过 C-D3 rev-3 round-5/6/7、rev-ogpt 多轮评审，`issuedReady`/ticket-ownership/fail-forward 是刻意设计。**Fix 1 只读 readiness、不改变 begin/complete 语义**，是最小侵入；Fix 2a 用同一 ticket complete，符合 ticket-ownership 契约（`requireCurrentReconfigureTicket` 会校验 marker）。

---

## 9. 涉及文件清单

| 文件 | 角色 |
|---|---|
| `app/src/main/java/cn/vectory/ocdroid/data/repository/SlimSseStateMachine.kt` | readiness 锁存位本体（:57/:168/:247）；Fix 1a 加 `isReady()`；Fix 3 埋点 |
| `app/src/main/java/cn/vectory/ocdroid/data/repository/OpenCodeRepository.kt` | `configure` begin/complete（:930/:967/:1006）；Fix 1b 加转发 |
| `app/src/main/java/cn/vectory/ocdroid/service/streaming/ConnectionBootstrapEngine.kt` | `performAttempt` 跳过 configure（:112-128）；**Fix 1c 主改动点** |
| `app/src/main/java/cn/vectory/ocdroid/ui/controller/HostProfileController.kt` | `resetLocalDataAndResync` dummy ticket（:849）；Fix 2 |
| `app/src/main/java/cn/vectory/ocdroid/ui/controller/ConnectionHealthProbe.kt` | `testConnectionWithEngine`（:607-735）/ `coldStartReconnect`（:757）；理解"硬刷新"链路，一般无需改 |
| `app/src/main/java/cn/vectory/ocdroid/ui/MessageActions.kt` | `loadMessages` 重试（:146）；理解症状，不建议在此改 |
| 测试 | `OpenCodeRepositorySlimapiEndpointsTest` / `SlimSseStateMachineRaceTest` / `SessionSyncCoordinatorSlimTest` / `SessionSyncDeadlockRegressionTest` |

---

## 10. 一句话结论

`slimIncarnationReady` 是个无自愈的粘性位，任一次未完成的 reconfigure 都会让整个进程的 slim 加载/提交永久失效；bootstrap engine 在 host 不变时跳过 `configure`，导致硬刷新也无法复位。**Fix 1（engine 在 readiness=false 时强制 configure）是最小、最稳健的根治**，建议配合 Fix 2（resetLocal 不再 dummy brick）与 Fix 3（埋点）。
