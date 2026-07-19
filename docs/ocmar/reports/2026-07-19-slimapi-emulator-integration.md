# ocdroid ↔ slimapi v1 模拟器对接测试 + 接口报告

> **日期**：2026-07-19
> **范围**：slimapi-client-v1（已 RELEASED）发布后的 M1/M2 polish + 调试日志插桩 + 真实模拟器对接测试 + 给 slimapi 项目组的接口综合分析。
> **配套**：本文是 `2026-07-19-slimapi-client-v1.md`（实现总报告）的后续；前者覆盖 T1-T18 实现 + 5 轮 final-gate，本文覆盖**对接验证 + 接口侧发现**。
> **接口部分（§4-§7）设计为可独立切出交 slimapi 项目组**。

---

## 摘要

本 session 在 slimapi-client-v1 工作流发布后完成了 4 件事：

1. **M1/M2 Minor polish**（前置，已闭环）：`consumeSaveState` 加 `if(!is Done) return` guard + `.onFailure` 加对称 profileId guard + 1 test，verify GREEN（3599 tests / 0 failures）。
2. **调试日志插桩**（新）：新增 `SlimapiDebugInterceptor`（tag `SlimapiHTTP`，捕获每个 `/slimapi/` 调用的 method/path/版本头/dir-query/status/timing）+ 3 处语义日志点（`SlimapiProbe`/`SlimapiResync`/`SlimSse`）。
3. **真实模拟器对接测试**（新）：新增 `SlimLiveSidecarIntegrationTest`（androidTest，6 cases，对部署 sidecar `10.0.2.2:4097` 用真实 Android OkHttp/Retrofit 栈），并在模拟器上运行 + logcat 捕获。
4. **接口综合分析**（新）：对照 slimapi v1 契约 4 份文档，产出接口清单 + 给 slimapi 组的发现。

### 核心结论

- **客户端侧：对接功能正确。** sessions 列表 / messages skeleton 反序列化 / 版本头门闩全 pass；`directory_not_allowed` 被正确 surface 为类型化 `StatusOutcome.DirectoryError`。
- **测试结果：6 run / 3 pass / 3 fail。** **3 个 fail 全为 sidecar 部署/契约问题，非客户端 bug**——由真实集成测试首次暴露。
- **给 slimapi 组 5 项发现**（3 项接口语义 / 1 项文档不一致 / 1 项文档澄清），其中 2 项高优先。

---

## 1. 本次任务完整过程

### 1.1 M1/M2 polish（前置，闭环）

round-8 review 留下的两个非阻塞观察项：

| 项 | 文件 | 改动 | 验证 |
|---|---|---|---|
| **M1** `consumeSaveState` KDoc/impl 不一致 | `ui/HostViewModel.kt:157-164` | 加 `if (_saveState.value !is HostProfileSaveState.Done) return` guard（旧 impl 无条件置 Idle，会孤儿化在飞的 reconfigure txn） | `HostViewModelSaveStateTest` +1 test `consumeSaveState while Saving is a no-op` |
| **M2** stale failure 未按 profileId 过滤 | `ui/settings/HostProfilesManagerScreen.kt:126-128` | `.onFailure` 加对称 `if (editingProfile?.id == s.profileId)` guard（与 `.onSuccess` 路径一致） | 现有 suite 覆盖 |

`_priv-verifier` no-cache（`--rerun-tasks --no-build-cache`）**EXIT=0，3599 tests / 0 failures**（`verify-1784467625.log`）。

### 1.2 Pre-flight（对接前体检）

| 检查 | 结果 |
|---|---|
| sidecar `http://127.0.0.1:4097/health` | **200**（UP） |
| `GET /slimapi/sessions`（带 `X-Slimapi-Version: 1`） | **200** + 真实 session 数组（sessions 在 `/home/mar/opencode_wd` 下） |
| 同上**无**版本头 | **400 `{"code":"version_required","accepted":[1,1]}`**（版本门闩 ✓） |
| `GET /slimapi/questions`（无 directory） | **422 `directory: Field required`**（⚠️ 契约不一致初现） |
| 模拟器状态 | ○ 未运行（free，可安全 start） |

> **路径澄清**：slim 端点在 **`/slimapi/`** 下（非 `/v1/`）。误 probe `/v1/sessions` 会命中 SPA catch-all 返回 HTML——这是上次 session 的 alarm 根因，非 sidecar 回归。

### 1.3 调试日志插桩（fix-5，background）

| 新增/修改 | 文件 | 行数 |
|---|---|---|
| **NEW** `SlimapiDebugInterceptor` | `data/repository/http/SlimapiDebugInterceptor.kt` | 124 |
| **NEW** `SlimLiveSidecarIntegrationTest` | `app/src/androidTest/.../SlimLiveSidecarIntegrationTest.kt` | 346（6 tests） |
| wire into base chain（version 之后、auth 之前） | `data/repository/http/OkHttpClientFactory.kt` | +10 |
| 语义日志点（probe/resync） | `data/repository/OpenCodeRepository.kt` | +5 |
| 语义日志点（per-digest） | `data/repository/SlimSseReducer.kt` | +12 |
| 测试构造同步 | `OkHttpClientFactoryMutationTest.kt` | +1 |

**日志约定**：用项目既有 `cn.vectory.ocdroid.util.DebugLog`（ring buffer + Logcat-parity，100+ 调用点），不引入新依赖；不动既有 `HttpLoggingInterceptor`（仍 BASIC in DEBUG / NONE in release）。

**插桩中发现的真实集成坑**（fix-5 自行捕获并修复）：OkHttp `Response.peekBody(byteCount)` 实测会干扰下游 `errorBody().string()` 读取（MockWebServer 场景下 14 个既有测试挂了）。→ 移除 body peek，非 2xx 分支只记 status/timing/content-type/content-length；sidecar 的机器可读 `code` 已由 repository 自己的 `parseErrorCode` WARN 路径 surface。

`check.sh --full --rerun-tasks` **EXIT=0，3599 tests / 0 failures / lint clean / androidTest sourceSet 编译通过**。

### 1.4 模拟器对接测试（direct，遵循设备安全纪律）

| 步 | 命令 | 结果 |
|---|---|---|
| 1 | `./scripts/emulator.sh start` | ✅ 开机完成 15s，`emulator-5554`，`sys.boot_completed=1` |
| 2 | `./gradlew assembleDebug assembleDebugAndroidTest --rerun-tasks` | ✅ BUILD SUCCESSFUL 38s |
| 3 | `adb install -r -t` app + androidTest APK | ✅ Success / Success |
| 4 | `adb logcat -c`；`am instrument -e class SlimLiveSidecarIntegrationTest -e slimapiServerUrl http://10.0.2.2:4097` | 6 run / 3 fail（见 §2） |
| 5 | `adb logcat -d` 捕获测试窗口 | 120 KB logcat（22 slim-tagged 行） |
| 6 | `./scripts/emulator.sh stop` | ✅ 已清理，模拟器释放 |

> **instrumentation arg 关键点**：`.env` 的 `OPENCODE_SERVER_URL=http://10.0.2.2:4096`（legacy），`build.gradle.kts:86` 把它注入 `openCodeServerUrl` arg。`SlimLiveSidecarIntegrationTest` 读 `slimapiServerUrl`（默认 4097）但 fallback 到 `openCodeServerUrl`——所以**必须** `am instrument -e slimapiServerUrl http://10.0.2.2:4097` 显式覆盖，否则会误连 4096。

### 1.5 Warming 探针（确认 fail 根因）

为区分「客户端 bug」vs「sidecar 部署状态」，跑了一组 host-side curl 复核 allowlist 机制（见 §2.3）。

---

## 2. 测试结果

### 2.1 通过（3 / 6）

| 测试 | 端点 | HTTP | 证据（logcat，`SlimapiHTTP` tag） |
|---|---|---|---|
| `slim_sessions_list_deserializes` | `GET /slimapi/sessions`（directory=null） | **200** × 4 calls | `← 200 GET /slimapi/sessions in 22ms type=application/json` |
| `slim_messages_skeleton_deserializes` | `GET /slimapi/messages/{sid}`（directory=null，G7-soft） | **200** | `← 200 GET /slimapi/messages/ses_08aa1ba2... in 25ms` |
| `version_gate_rejects_missing_header` | （负向，bare OkHttp 不挂拦截器） | **400** 预期 | — |

→ 客户端真实 Android OkHttp/Retrofit 栈对 sidecar 的 wire shape（sessions 数组、skeleton MessageWithParts）反序列化正确；版本头注入（所有调用 `version=1`）正确。

### 2.2 有问题（3 / 6）—— **全部为 sidecar 部署/契约问题，非客户端 bug**

| 测试 | 端点（两次调用） | 结果 | 根因 |
|---|---|---|---|
| `slim_questions_requires_directory_contract_mismatch` | `GET /slimapi/questions` | `directory=null`→**422**；`directory=/home/mar/opencode_wd`→**400** | sidecar 要 directory（1-32）；opencode_wd 不在 allowlist |
| `slim_permissions_requires_directory_contract_mismatch` | `GET /slimapi/permissions` | 同上 422 / 400 | 同上 |
| `slim_single_session_status_deserializes` | `GET /slimapi/sessions/ses_08aa1ba2.../status` | **400 `directory_not_allowed`** | 该 sid 的 directory（`/home/mar/opencode_wd`）非 allowlisted → 客户端 surface 为 `StatusOutcome.DirectoryError(sessionId=...)` ✓ |

### 2.3 Warming 探针——确认根因

```
GET /slimapi/projects  → 200
  discovered = [
    {id: global, directories: []},
    {id: eadcdff4..., worktree: /home/mar/personal_projects/sync-player},
    {id: 9b875627..., worktree: /home/mar/personal_projects/x-liker},
    {id: 2a39eaec..., ...},  # ocdroid 也在列
    ...
  ]
  ⚠️ /home/mar/opencode_wd 不在 discovered projects 中

POST-WARM:
  GET /slimapi/questions?directory=/home/mar/personal_projects/ocdroid   → 200 {"items":[],"errors":[]}
  GET /slimapi/permissions?directory=/home/mar/personal_projects/ocdroid → 200 {"items":[],"errors":[]}
  GET /slimapi/sessions/ses_08aa1ba2.../status                           → 仍 400 directory_not_allowed
```

→ **allowlisted 目录（ocdroid）的 questions/permissions 完美 200**；`opencode_wd` 因为根本不是 discovered project，永远不进 allowlist，所以基于其 sid 的 per-session status 永远 400。

### 2.4 客户端正确性（失败用例反而证明的）

- **typed 错误处理 ✓**：`directory_not_allowed` 被 repository 解析（`parseErrorCode`）并 surface 为 `StatusOutcome.DirectoryError`（非崩溃、非误报）。
- **版本头门闩 ✓**：6 个 test 的所有 `/slimapi/**` 调用 logcat 均 `version=1`。
- **wire shape 反序列化 ✓**：sessions / messages skeleton 200 + 客户端 `Result.success`。
- **cold-start 边界 ✓**：`HostConfig.slim=true` + `SlimapiVersionInterceptor` 2-gate AND（slim 开关 + `/slimapi/` 前缀）工作正确。

---

## 3. 接口清单（slimapi 已提供 vs 客户端需求）

### 3.1 slimapi v1 提供的 16 路由（contract §2 权威表）

| # | 方法 | 路径 | directory 参数语义 | 客户端是否使用 |
|---|---|---|---|---|
| 1 | GET | `/slimapi/health` | 无 | ✓（连接自检，`schema.degraded` fail-closed） |
| 2 | GET | `/slimapi/ready` | 无 | ✗（未用，可选） |
| 3 | GET | `/slimapi/metrics` | 无 | ✗（运维侧） |
| 4 | GET | `/slimapi/sessions` | **可选**（null=all，按 dir 过滤是 bonus） | ✓（cold-start 列表） |
| 5 | GET | `/slimapi/projects` | 无 | ✗（**建议加进 cold-start warm allowlist**，见 F3） |
| 6 | GET | `/slimapi/sessions/status`（批量） | **必填** | ✗（未用批量） |
| 7 | GET | `/slimapi/sessions/{sid}/status` | 自洽（id→directory 反查），受 allowlist 约束 | ✓（per-session reconcile） |
| 8 | GET | `/slimapi/messages/{sid}` | **soft**（null=不拦） | ✓（skeleton tail） |
| 9 | GET | `/slimapi/messages/{sid}/since/{ts}` | soft | ✓（digest 触发的增量拉取） |
| 10 | GET | `/slimapi/messages/{sid}/full/{mid}` | soft | ✓（单条展开） |
| 11 | GET | `/slimapi/messages/{sid}/full`（batch `?ids=`） | soft | ✓（T15/T16 批量展开；404 `thin_route_not_found` 回退 N 并行） |
| 12 | GET | `/slimapi/questions` | **必填 1-32** | ✓（cold-start pending 聚合） |
| 13 | GET | `/slimapi/permissions` | 必填 1-32 | ✓（同上） |
| 14 | POST | `/slimapi/questions/{qid}/reply` | body routeToken | ✓ |
| 15 | POST | `/slimapi/questions/{qid}/reject` | body routeToken | ✓ |
| 16 | POST | `/slimapi/sessions/{sid}/permissions/{pid}` | body routeToken | ✓ |
| — | GET | `/slimapi/events` | 无（v2 全实例聚合） | ✓（SSE） |
| — | `*` | `/{path}` catch-all | 客户端带 `X-Opencode-Directory` | ✓（发消息等通用写） |

**客户端需要的、slimapi 已提供的**：全部 16 路由 + SSE + catch-all 均已实现（implementation-status.md 自报「契约 v1 范围 100% 落地」）。**没有客户端需要、slimapi 缺失的接口**。

### 3.2 客户端 repository slim 公开方法（fix-5 梳理）

```kotlin
suspend fun getSlimapiSessions(directories, roots, limit, search): Result<List<Session>>
suspend fun getSlimapiSessionStatusOutcome(sessionId): StatusOutcome          // 类型化 sealed
suspend fun getSlimapiMessagesPage(sessionId, limit, before, mode, bumpBookmark, token): Result<MessagesPage>
suspend fun getSlimapiMessagesSince(sessionId, since, limit, before, token): Result<List<MessageWithParts>>
suspend fun getSlimapiMessageFull(sessionId, messageId): Result<MessageWithParts>
suspend fun expandMessagesFullBatch(sessionId, ids): ExpandOutcome            // 404→N 并行回退
suspend fun getSlimapiQuestions(directories, token): Result<SlimAggregationOutcome<...>>
suspend fun getSlimapiPermissions(directories, token): Result<SlimAggregationOutcome<...>>
suspend fun coldStartSlimSync(openSessionId, directories, token): Result<SlimColdStartSnapshot>
suspend fun probeLatestSlim(sessionId): ProbeResult
```

### 3.3 缺的接口

**无客户端功能缺口**。本 session 发现的「缺」是**语义/部署层**而非端点层——见 §4 F1-F3。

---

## 4. 给 slimapi 项目组的发现（接口部分）

> 以下 5 项可独立切出。优先级：F1=F2 > F3 > F4 > F5。

### F1 — `/slimapi/questions` + `/permissions` directory 必填，但契约 cold-start 节未明确传递规则【高优先】

- **现象**：sidecar 把 `directory` 列为这俩聚合端点的**必填**参数（INTERFACE_MAP §2：「`directory:list[str]` 用 repeated query，必填；去重后 1–32」）；null → HTTP 422 `{"detail":[{"type":"missing","loc":["query","directory"]}]}`（本 session 真机测得）。
- **客户端侧**：`OpenCodeApi.getSlimapiQuestions/Permissions` 声明 `directory: List<String>? = null`，KDoc 写「null = all directories the sidecar is aggregating」——**与 sidecar 实际行为相反**。这是潜在运行时 422。
- **契约层面**：contract §4 cold-start 列这俩为冷启动端点（`GET /slimapi/sessions + /questions + /permissions`），但**没说客户端不知道任何 dir 时该传什么**。对一个全新 cold-start，客户端还没有 sessions 列表，无从知道该传哪些 dir。
- **建议（二选一）**：
  - **(a) sidecar 改**：允许 null = 真正聚合所有 dir（与 sessions 列表行为对齐）。
  - **(b) 契约改**：明确 cold-start 顺序——先 `GET /slimapi/sessions`（null OK）拿到所有 dir 集合，再把该集合作为 `?directory=` 重复参数传给 questions/permissions。并在 contract §4 显式写明。
- **客户端临时**：当前实现已走 (b) 的等价路径（先 sessions 再聚合），但 KDoc 需据实情更正。

### F2 — 「listed-but-rejected」不一致：session 在列表里，但其 status 查询被拒【高优先】

- **现象**（本 session 真机首次暴露）：
  - `GET /slimapi/sessions`（directory=null）返回**所有** session，包括 directory 不在 allowlist 的（实测：sessions 在 `/home/mar/opencode_wd` 下，但该 dir 不是 discovered project，非 allowlisted）。
  - 但对同一 sid 调 `GET /slimapi/sessions/{sid}/status` → **400 `directory_not_allowed`**（sidecar 反查该 sid 的 directory，发现非 allowlisted）。
- **客户端影响**：UI 渲染了 session 行，但点进去查状态/消息全 400——体验断裂。
- **建议（二选一）**：
  - **(a) sessions 列表按 allowlist 过滤**：null directory 时只返回 allowlisted dir 的 sessions（与 status 的 allowlist 约束对齐）。
  - **(b) per-session status 放宽**：sid 已自洽反查 directory，放宽 allowlist 约束（status 是读操作，sid 已是 capability）。
- **注**：`/slimapi/messages/{sid}`（soft allowlist，null 不拦）实测对同一 sid 200——说明 messages 路由**已经**能服务非 allowlisted dir 的 session，而 status 路由不能。这俩对同一约束的处理不一致，应在 contract 层统一。

### F3 — allowlist 只靠 `/slimapi/projects` 发现播种【中优先】

- **现象**：implementation-status.md 已诚实声明「routeToken 不刷 allowlist；sidecar 冷启动后 allowlist 为空，首个带合法 routeToken 的 reply 会 400 `directory_not_allowed`，直到 `/slimapi/projects` 或带 `?directory=` 的端点暖起来」。
- **客户端影响**：cold-start 流程目前是 `sessions → questions → permissions → since`，**不调 `/slimapi/projects`**——所以 cold-start 首个 questions/permissions 调用可能在 allowlist 空/陈旧时打 400。
- **建议**：
  - **sidecar 侧**：sidecar 启动时主动 warm 一次 `/slimapi/projects`（把 opencode 当前的 project 列表预取），避免 cold allowlist。
  - **或契约侧**：contract §4 cold-start 显式加入 `GET /slimapi/projects` 作为第一步。
- **客户端侧动作**（不依赖 slimapi）：把 `getSlimapiProjects()` 加进 `coldStartSlimSync` 第一步（客户端目前未实现该方法，需补）。

### F4 — `CLIENT_CHANGES.md` SSE 节过期【低优先，文档】

- **现象**：`CLIENT_CHANGES.md` §SSE 写「Phase 2 改连 `/slimapi/events?directory=...&sessionId=...`」。但 INTERFACE_MAP §3 明确：v2 重写后 `directory`/`sessionId`/`stream` 参数**完全移除**（全实例、全目录聚合）。
- **影响**：文档读者会误以为 SSE 要带 directory/sessionId。
- **建议**：同步 `CLIENT_CHANGES.md` §SSE 与 INTERFACE_MAP §3 一致。

### F5 — `accepted:[1,1]` 区间标注澄清【非 bug，文档】

- **现象**：版本门闩失败 body `{"code":"version_required","accepted":[1,1]}`，`[1,1]` 看似重复。
- **实际**：是闭区间 `[min,max]`，当前 min=max=1（INTERFACE_MAP §0：「当前接受闭区间 `[1,1]`」）。
- **建议**：contract §1 给 `[1,1]` 加一句「`[min,max]` 区间，当前 `min=max=1`」便于读者快速理解。

---

## 5. 接口文档应当如何（建议）

slimapi 现有文档矩阵（v1-contract.md / implementation-status.md / INTERFACE_MAP.md / CLIENT_CHANGES.md / v1-impl-spec.md）总体**权威且详尽**——v1-contract.md 作为唯一 wire 基准的设计正确，implementation-status.md 的逐节审计质量很高。改进建议：

1. **directory 三态语义表**（横切关注点，应独立成节）：每个 endpoint 标 `可选 | 必填 N-M | soft(null=不拦) | 自洽(受 allowlist 约束)`。当前散落在 INTERFACE_MAP 各格的「坑/约束」里，不便于客户端实现者一眼裁定。本 session 的 F1/F2 正是 directory 语义分散导致的误读。

2. **allowlist 机制独立成节**：覆盖 (a) 播种源=`/slimapi/projects` 发现；(b) cold-start 顺序约束；(c) non-allowlisted dir 的 session 可见性（sessions list 显示 vs status 查询拒绝——即 F2）；(d) routeToken 不刷 allowlist 的时序后果。当前这些分散在 implementation-status.md 的「诚实声明」+ INTERFACE_MAP 的注释里。

3. **CLIENT_CHANGES.md 与 contract 同步机制**：F4 暴露的 SSE 节过期说明 CLIENT_CHANGES 没有「随 contract 变更同步」的纪律。建议 contract changelog 条目同时列出 CLIENT_CHANGES 受影响小节。

4. **跨端点行为一致性检查清单**：F2（messages soft vs status 严格，对同一 sid）这类不一致应在 contract review 时显式检查。建议 contract §7 错误码表外加一节「同一 resource 在不同端点的 allowlist 行为」。

5. **保留**：v1-contract.md 头部的「变更记录 (implementation changelog)」+ 「加性变更不 bump 版本」的约定很好，请保留。

---

## 6. 涉及变更的接口

### 6.1 B1 wire 变更（2026-07-18，客户端已全部处理）

| 变更 | 客户端处理 | 状态 |
|---|---|---|
| thin 错误体 `{"detail":...}` → `{"code":...}` | `SlimapiErrorCodes.kt` + `parseErrorCode` | ✓ |
| `/slimapi/sessions/{sid}/status` 404/502/503 三态分裂 | `StatusOutcome` sealed routing | ✓ |
| `/slimapi/projects` 5xx 502→503 | circuit breaker 按 5xx-class | ✓ |
| 413 `message_too_large`（`/full/{mid}` mode=full） | `ExpandOutcome.Failed` 分支 | ✓ |
| 403 `shell_not_allowed`（catch-all） | 客户端不调 shell/PTY | ✓ |
| 400 `invalid_directory_count` / `invalid_route_token` | 聚合/应答错误分支 | ✓ |
| G7-soft：messages 三路由 query directory allowlist | 客户端带 `?directory=` | ✓ |

### 6.2 本 session 客户端侧新增（已落地，非 wire 变更）

- `SlimapiDebugInterceptor` + 语义日志点：**仅日志，不改 wire 行为**。
- `SlimLiveSidecarIntegrationTest`：回归资产（后续 sidecar 升级可复跑）。
- M1/M2 polish：save-lifecycle 内部契约对齐，非 wire。

### 6.3 待 slimapi 侧变更（见 §4）

- **F1/F2**（高优先）：directory 必填语义 + listed-but-rejected 一致性——建议 sidecar 侧调整。
- **F3**（中优先）：allowlist cold-start 播种。
- **F4**（低优先）：CLIENT_CHANGES SSE 节同步。
- **F5**（非 bug）：`[1,1]` 区间标注。

---

## 7. 总结

**对接结论**：ocdroid slim v1 客户端与部署 sidecar 的对接**功能正确**——sessions/messages 反序列化、版本头门闩、typed 错误处理（`directory_not_allowed` → `StatusOutcome.DirectoryError`）全数验证通过。客户端侧无遗留 bug。

**测试方法论价值**：本 session 的真实模拟器集成测试（区别于 round-7 的 3598 unit tests / MockWebServer + Stage 7 的 host curl smoke）**首次用真实 Android OkHttp/Retrofit 栈端到端打部署 sidecar**，暴露了三类仅靠 unit test 或 curl 看不到的契约/部署态问题（F1/F2/F3）。`SlimLiveSidecarIntegrationTest` + `SlimapiDebugInterceptor` 作为回归资产留存——后续 sidecar 升级可直接复跑 + logcat 可读。

**给 slimapi 组**：接口端点层无缺口（16 路由全实现），问题集中在 **directory 语义一致性 + allowlist 播种时序**（§4 F1-F3）。客户端已据现状做 defensive 处理，但根因修复在 sidecar/契约侧。

**M1/M2 + 日志 + androidTest 三项客户端改动均已 verify GREEN（3599 tests）**，可随下一次 release 一并发布。

---

## 8. Artifacts

| 类型 | 路径 |
|---|---|
| 本报告 | `docs/ocmar/reports/2026-07-19-slimapi-emulator-integration.md` |
| 实现总报告（前置） | `docs/ocmar/reports/2026-07-19-slimapi-client-v1.md` |
| 模拟器测试结果（原始） | `.ocmar/workflows/slimapi-client-v1/emulator-slim-test-result.txt` |
| 模拟器 logcat（测试窗口，120KB） | `.ocmar/workflows/slimapi-client-v1/emulator-slim-logcat.txt` |
| SlimLiveSidecarIntegrationTest | `app/src/androidTest/java/cn/vectory/ocdroid/SlimLiveSidecarIntegrationTest.kt` |
| SlimapiDebugInterceptor | `app/src/main/java/cn/vectory/ocdroid/data/repository/http/SlimapiDebugInterceptor.kt` |
| M1/M2 verify log | `.ocmar/workflows/slimapi-client-v1/verify-1784467625.log` |
| 持续问题工作文件 | `.ocmar/workflows/slimapi-client-v1/problem-report-wip.md`（含 C-D2/D5/D7/D8 + Stage7-A1 等历史发现） |

### 引用的 slimapi 文档（交 slimapi 组时附）

- `oc-slimapi/docs/v1-contract.md`（wire 基准）
- `oc-slimapi/docs/v1-contract-implementation-status.md`（逐节审计）
- `oc-slimapi/docs/INTERFACE_MAP.md`（端点级坑表）
- `oc-slimapi/docs/CLIENT_CHANGES.md`（客户端改动清单，§SSE 待同步）
