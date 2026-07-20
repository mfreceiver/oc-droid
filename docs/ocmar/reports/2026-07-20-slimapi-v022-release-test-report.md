# oc-slimapi v0.2.2 — ocdroid 发布与联调实测报告

| | |
|---|---|
| **日期** | 2026-07-20 |
| **范围** | ocdroid v0.11.5 发布 + 针对 oc-slimapi v0.2.2 的 live 联调实测（F-1 runtime handoff 收口） |
| **关联** | 代码交付见 [`2026-07-20-slimapi-v022-client-adapt.md`](2026-07-20-slimapi-v022-client-adapt.md)；适配需求源见 oc-slimapi 侧 [`2026-07-20-v0.2.2-ocdroid-handoff.md`](../../../../oc-slimapi/docs/ocmar/reports/2026-07-20-v0.2.2-ocdroid-handoff.md) §2/§5 |
| **HEAD** | `807ed52`（tag `v0.11.5`，已发布）→ `4f26a6f`（联调测试修复，post-release）→ 本次报告 + KDoc 刷新（待 commit） |
| **sidecar** | `http://127.0.0.1:4097` = **v0.2.2**（`sidecar.version=0.2.2`、`api_version=1`、`schema.degraded=false`）；emulator 经 `10.0.2.2:4097` |
| **评审** | rev-opus（独立终审，6 维度）**NEEDS-FIX 9.0** → 全部 Important/Minor 已采纳修订（见末尾「评审处置」） |

---

## 1. 发布产物（v0.11.5）

| 项 | 值 |
|---|---|
| 版本 | `v0.11.5`（自 v0.11.4 patch；`versionName`=git describe、`versionCode`=commit count，git 派生） |
| tag | `v0.11.5` @ `807ed52`（annotated tag `fece9c8` peel 到 `807ed52`） |
| push | Gitea `origin`（main + tag）✅；github 镜像 **未 push** |
| Gitea release | 528 — https://git.vectory.cn:18443/mfreceiver/oc-droid/releases/tag/v0.11.5 |
| APK | `APK/oc-droid-0.11.5-807ed52.apk`（11.6 MB，已上传 release 528） |
| 编译门控 | `./scripts/check.sh` BUILD SUCCESSFUL（3m24s，clean committed state） |
| 代码评审 | final whole-branch rev-grok **APPROVED (0C/0I)** + fresh verifier **EXIT=0/FAILURES=0**（详见交付报告 §5） |

> **commit 状态消歧**（跨文档一致性）：交付报告（`2026-07-20-slimapi-v022-client-adapt.md` §6）快照时点为实现 working-tree **未 commit**（base `401792f`，ocmar 默认）；**本轮发布已将其 commit 于 `807ed52` 并 tag `v0.11.5`**。两份报告时点不同，非矛盾。`git log` 证实 `401792f → 807ed52 → 4f26a6f` 为真实 commit 链。

---

## 2. Live 联调实测结果

### 2.1 instrumented test（`SlimLiveSidecarIntegrationTest`）

端到端跑 3 轮，emulator（`./scripts/emulator.sh` start→run→stop 纪律遵守，用完已清理），sidecar `http://10.0.2.2:4097`。

| 轮次 | 结果 | 定位 / 处置 |
|---|---|---|
| run-1 | 6 tests / **2 failures** | `slim_{questions,permissions}_requires_directory_contract_mismatch` 失败 —— **stale 测试**：断言 pre-F1 的「null dir→422」；F1（slimapi v0.2.2）已把 `?directory` 改可选（null=聚合）。其余 4 测（sessions list / version-gate / session status / messages skeleton）通过 |
| run-2 | branch-1 ✅ / **branch-2 ✗** | 改 stale 测试为 `_null_directory_aggregates`（断言 isSuccess + Success/Partial）后 branch-1 绿（F1 live 确认）；但暴露 branch-2（with-directory）失败 |
| run-3 | **6/6 绿**（0 failures / 0 errors / 0 skipped，BUILD SUCCESSFUL in 24s） | branch-2 经 curl 定位为**环境脆弱**（非 bug），移除该 live 分支后全绿。测试修复 commit `4f26a6f` |

**最终 6 测全绿清单**：

| test | 验证点 | live 证据 |
|---|---|---|
| `slim_sessions_list_deserializes` | `GET /slimapi/sessions`（null dir）→ 200 + 每行含 id/directory/time | ✅ 反序列化成功，sessions 非空 |
| `version_gate_rejects_missing_header` | 裸 OkHttp（无 `X-Slimapi-Version`）→ 400 `version_required` | ✅ HTTP 400 + body 含 `version_required` |
| `slim_single_session_status_deserializes` | `GET /slimapi/sessions/{sid}/status` → Success/Retry | ✅ Success，`status.type ∈ {idle,busy,retry}` |
| `slim_messages_skeleton_deserializes` | `GET /slimapi/messages/{sid}`（skeleton）→ List | ✅ 反序列化成功，每条含 info.id/role |
| `slim_questions_null_directory_aggregates` | `GET /slimapi/questions`（null dir）→ 200 聚合 | ✅ F1 确认（断言 isSuccess + Success/Partial；`scope.directories=21` 值由 §2.2 curl 佐证，**非本测断言**） |
| `slim_permissions_null_directory_aggregates` | `GET /slimapi/permissions`（null dir）→ 200 聚合 | ✅ F1 确认（与 questions 对称） |

### 2.2 curl 直证（host loopback `127.0.0.1:4097`）

| 探针 | 结果 | 结论 |
|---|---|---|
| `GET /slimapi/health`（无 version header） | **400** | 版本门控独立于客户端 interceptor 生效（gate 正确） |
| `GET /slimapi/health`（带 `X-Slimapi-Version: 1`） | **200** `{"sidecar":{"ok":true,"version":"0.2.2"},"server":{"api_version":1,...},"schema":{"degraded":false}}` | v0.2.2 healthy，schema 未降级 |
| `GET /slimapi/questions`（null dir，带 header） | **200** `{"items":[],"errors":[],"scope":{"directories":21}}` | **F1 live 确认**：null dir=聚合；`scope.directories=21`（N>0，走 replace 路径） |
| `GET /slimapi/questions?directory=/project`（带 header） | **400** `directory_not_allowed` | allowlist §13 正确：非 allowlist dir 被拒 |
| `GET /slimapi/questions?directory=/nonexistent`（带 header） | **400** `directory_not_allowed` | 同上（非存在性判定，是 allowlist 判定） |

---

## 3. 契约符合性（ocdroid v0.11.5 vs oc-slimapi v0.2.2）

逐条对照 handoff §2（ocdroid 改动清单）+ §5（联调要点）。

| # | handoff 要点 | ocdroid 实现 | 验证强度 | 状态 |
|---|---|---|---|---|
| 1 | `/since` 真过滤修复（Gap1 真 bug：previously no-op） | ocdroid 消费方 watermark 升级为 `(updatedAt, messageID)` 二元组字典序；`compareWatermark` 纯函数统一 4 站点 | 单测（compareWatermark 矩阵 + 推进反转）+ messageID 单调性源码核实（`id.ts` ascending）；**live 同 ts 边界未构造**（需工程化并行产消息） | ◐ 逻辑扎实，live 边界留 follow-up |
| 2 | tie-break `(updatedAt, messageID)`（Gap1） | 同上；strict `>` 推进两维 | 单测全覆盖 + opencode id.ts 严格单调（含同 ms）源证 | ◐ 同上 |
| 3 | `/sessions` 列表 coded-error（Gap2 真 bug） | `parseErrorCode`→internal + log + rethrow 原始（不吞 code）；按"非 200=失败"粗判行为不变 | 单测（503/502 code log+rethrow）；live 未触发失败路径（sidecar 健康） | ◐ 失败路径单测覆盖，live 无可观测失败 |
| 4 | q/p envelope `scope.directories`（Gap2 加性） | `SlimapiScope` DTO + `serverScope` + N==0 retain-prior gating（Success **和** Partial） | envelope 透传 **live 确认**（N=21>0 走 replace 路径）；**但 N==0 retain-prior 分支（P1 真正修复点）live 从未触发**（sidecar 恒 N>0），仅单测覆盖 | ◐ envelope live；N==0 修复单测 |
| 5 | q/p 显式 directory 规范化去重（rev-13） | `normalizeDirectory`（server-facing）+ fan-out normalize-dedup + onResync audit | 单测（normalizeDirectory 矩阵 + fan-out dedup）；live 仅 observe **拒绝路径**（`directory_not_allowed`，反向证据），**无 allowlisted dir→200 dedup 正向 live 证据**（见 §5.3 盲区） | ◐ dedup 正向单测；live 仅反向 |
| 6 | `/since/0` 合法但推荐 cursor drain | 初始拉取走 `?before` 分页，非 `/since/0` | 单测 + 代码路径 | ✅ |
| 7 | 503 全失败不含 `scope` | ocdroid 仅在 200 envelope 消费 `scope`；503 路径 Result.failure | 单测 | ✅ |
| 8 | version-gate `X-Slimapi-Version: 1` | `SlimapiVersionInterceptor`（`HostConfig.slim` + `/slimapi/` 前缀双门控） | **live 确认**（裸 OkHttp→400 `version_required`） | ✅ |

---

## 4. F-1 runtime handoff 收口

交付报告将 F-1（`/since` 真过滤 + tie-break 联调）标 Final-only，留用户联调。本轮收口：

| 子项 | 收口状态 |
|---|---|
| **scope.directories envelope 透传**（P1） | ✅ **live 收口**：null-dir aggregate → 200 `scope.directories=21`（实测 N>0，走 replace 路径）。注：P1 真正修复点「N==0 retain-prior」分支 **live 从未触发**（sidecar 恒 N>0），仅单测覆盖 |
| **version-gate** | ✅ **live 收口**：400 `version_required` 独立于 interceptor 生效 |
| **sessions/messages/q/p 反序列化**（200 路径） | ✅ **live 收口**：6 instrumented 测全绿 |
| **allowlist §13**（directory 守卫） | ✅ **live 收口**：`directory_not_allowed` 对非 allowlist dir 正确返回 |
| **`/since` 真过滤 + 同 ts tie-break**（P0 核心） | ◐ **逻辑收口、live 边界未构造**：messageID 单调性在 spec 阶段源码核实并记录于 spec §2.2（`packages/opencode/src/id/id.ts`，ascending，`msg_`+12hex(`ts*4096+counter`)，同 ms counter 自增→字典序严格单调，含 `:54-58` 行级证据）；`compareWatermark` 4 站点单测全覆盖。**两点保留**：(a) 该 `id.ts` 为上游 opencode 路径，**未 vendor 入本仓，且本轮在交付边界内不可复核**（host 上未检出该 checkout）——源证仅可从 spec §2.2 记录复现，非 handoff 时点独立可验；(b) 「严格单调」前提为 **messageID 恒非空**，若上游/中游出现 `info.id==null` 的 malformed item，`onReconcileSuccess` 在 ts 前进时会产生从未对应真实消息的 split pair（与该函数 kdoc「Splitting the pair ... is impossible」措辞冲突，窄边界，需 malformed 触发，见 §5.4）。live 未工程化构造同 `updatedAt` 不同 `messageID` 边界，留 follow-up（非阻塞：逻辑可证、服务端 v0.2.2 已部署真过滤） |
| **`/sessions` 失败路径 coded-error**（P2a） | ◐ **单测收口、live 未触发**：sidecar 本轮全程健康，无 502/503 可观测；单测覆盖 `upstream_http_N`/`upstream_unavailable` code log+rethrow |

**结论**：F-1 的**可观测运行时行为**已 live 收口（scope envelope 透传/version-gate/反序列化/allowlist）；P1 的 N==0 修复分支 + P0 纯函数由单测+源证收口，各自 live 边界为非阻塞 follow-up。

---

## 5. 本轮发现

1. **stale instrumented test（F1 未同步到 androidTest）**：`SlimLiveSidecarIntegrationTest` 的 q/p 测试断言 + 类级/方法级 KDoc 均仍描述 pre-F1 的「null dir→422 / CONTRACT MISMATCH」。根因：androidTest 不在 `check.sh`（需设备+sidecar），F1 契约演进未回灌。已修：断言改 `isSuccess + Success/Partial`（commit `4f26a6f`），KDoc 刷新到 F1 语义（本次，fixer-grok，`compileDebugAndroidTestKotlin` BUILD SUCCESSFUL）。**建议**：契约演进时同步审视 androidTest（当前 check.sh 盲区）。
2. **branch-2 环境脆弱性**：with-directory live 分支从 `sessions.first().directory`（`/home/mar/opencode_wd`）取 dir，但该路径不在 sidecar allowlist（`/home/mar/personal_projects/*`，21 dir）→ 400 `directory_not_allowed`（契约 §13 正确，非 bug）。ocdroid 无 `slimapi/projects` accessor 取 allowlist dir，故该 live 分支环境脆弱。with-dir success 已由单测（SessionSyncCoordinatorSlimTest / SlimapiV1ModelsTest）覆盖，移除 live branch-2 留注释，聚焦高价值 F1 null-dir aggregate。
3. **live 覆盖盲区（with-directory 200 fan-out）**：移除 branch-2 + curl 仅 observe 拒绝路径（400）→ 生产主路径「显式 directory fan-out → 200 成功 + normalize-dedup 实际生效」**零 live 正向证据**。dedup 正向行为仅单测覆盖。与 P0 live 边界同列为非阻塞 follow-up（构造 200 正反馈需 allowlisted dir 或新增 `slimapi/projects` accessor）。
4. **`onReconcileSuccess` null-id 窄边界（代码级 follow-up）**：若 tuple-max item 的 `info.time.updated>0` 但 `info.id==null`（模型允许 nullable id），`observedTs>priorTs` 时 `advances=true`（ts 支配）而 `newMessageId` 停在 prior → 产生从未对应真实消息的 split pair，与该函数 kdoc「Splitting the pair ... is impossible」措辞冲突。需 malformed item 触发，极窄；非本批引入，记为 tracked follow-up（修正 kdoc 措辞 + 评估是否对 null-id 防御）。

---

## 6. oc-slimapi 侧待确认事项（handoff 反向）

本轮联调后，以下事项需 slimapi 侧确认（影响 ocdroid 正确性假设）：

1. 🔥 **透传 opencode 原始 messageID（勿重映射）**：ocdroid 的 `(updatedAt, messageID)` tie-break 直接对 messageID 做字典序 String 比较（`SlimapiResync.compareWatermark`），依赖 messageID **字典序严格单调**（即 opencode 原始发射顺序）。opencode 侧单调性在 spec 阶段源码核实（`id.ts` ascending，同 ms counter 自增）——但该上游路径**未 vendor 入 ocdroid、handoff 时点在交付边界内不可独立复核**（见 §4 保留点 a）。**故 slimapi 侧的透传确认是 ocdroid 正确性的权威核验点**：若 slimapi 重映射/重生 ID（或在聚合 fan-out 层改写），tie-break 语义破裂。请确认 slimapi 对 messageID 纯透传（含聚合 fan-out 不改写）。
2. **`Partial + scope.directories==0` 是否可能**：ocdroid 对 Success **和** Partial 都做 N==0 retain-prior（安全超集，堵 Partial+empty+empty-allowlist 残留洞）。若 slimapi 永不发送 Partial+N==0，该 gate 为无害防御；若可能，gate 必要。请确认。
3. **`/sessions` 错误体消费深度**：ocdroid 当前对 sessions 失败仅 **log code + rethrow 原始**（`parseErrorCode`→internal），**不**按具体 code（`upstream_http_404` vs `upstream_unavailable`）分支决策。若 slimapi 期望 ocdroid 按 code 差异化处理，需协商 carrier + 分支契约。当前 = 最小深度（fail + log code）。

---

## 7. 评审处置（rev-opus NEEDS-FIX 9.0 → 全采纳）

| 级别 | 项 | 处置 |
|---|---|---|
| Important | I-1 §3 行4 把「未 live 触发的 N==0 retain 修复」标 ✅「gate 实战」 | ✅ 降级为 ◐，证据列拆分「envelope live / N==0 修复单测」（§3 行4 + §4 + §2.1） |
| Important | I-2 P0 `id.ts` 源证路径在交付边界内不可复核 | ✅ §4 保留点 (a) 明示「未 vendor、host 未检出、仅可从 spec §2.2 记录复现」；§6 item1 据此把 slimapi 透传确认提为权威核验点 |
| Important | I-3 with-directory 200 fan-out live 盲区未标 | ✅ §3 行5 降级为 ◐（live 仅反向证据）+ §5.3 新增盲区条目 |
| Minor | M-1 §2.1 把 curl 的 `directories=21` 归因到 instrumented 测 | ✅ §2.1 该行标注「值由 §2.2 curl 佐证，非本测断言」 |
| Minor | M-2 跨文档 commit 状态不一致 | ✅ §1 新增「commit 状态消歧」段 |
| Minor | M-3 测试 KDoc 仍 stale（422/CONTRACT MISMATCH/不存在方法名） | ✅ fixer-grok 刷新 3 处 KDoc 到 F1 语义，`compileDebugAndroidTestKotlin` BUILD SUCCESSFUL |
| Minor | M-4 `onReconcileSuccess` null-id 与 kdoc「impossible」冲突 | ✅ §4 保留点 (b) + §5.4 新增代码级 follow-up |

无 Critical。rev-opus 确认核心实现声明经源码逐条核实为真（`compareWatermark` 4 站点、scope 仅 200 消费、git refs、N=21 均一致），瑕疵仅为收口强度措辞高估，已全部降级/补证。

---

## 8. 可审计引用

- **代码交付报告**：`docs/ocmar/reports/2026-07-20-slimapi-v022-client-adapt.md`
- **spec / plan**：`docs/ocmar/specs|plans/2026-07-20-slimapi-v022-client-adapt*.md`（P0 源证记录见 spec §2.2 / plan:18,:462）
- **instrumented 测试**：`app/src/androidTest/java/cn/vectory/ocdroid/SlimLiveSidecarIntegrationTest.kt`
- **androidTest 报告**（run-3，6/6 绿）：`app/build/outputs/androidTest-results/connected/debug/TEST-ocdroid(AVD)*.xml`
- **minor-ledger**（D1-D8 全清）：`.ocmar/workflows/slimapi-v022-client-adapt/minor-ledger.md`
- **slimapi handoff**（适配需求源 + §5 联调要点）：`/home/mar/personal_projects/oc-slimapi/docs/ocmar/reports/2026-07-20-v0.2.2-ocdroid-handoff.md`
- **slimapi 契约**（rev D）：`/home/mar/personal_projects/oc-slimapi/docs/v1-contract.md`
- **commits**：`807ed52`（v0.11.5 主体）→ `4f26a6f`（联调测试修复）→ 本次（报告 + KDoc 刷新，待 commit）

---

> **一句话**：ocdroid v0.11.5 已发布（Gitea release 528）；针对 slimapi v0.2.2 的 live 联调 6/6 绿，F-1 runtime handoff 的**可观测部分** live 收口（scope envelope 透传/version-gate/反序列化/allowlist），P1 N==0 修复分支 + P0 纯函数由单测+源证收口、各自 live 边界为非阻塞 follow-up；rev-opus 终审 NEEDS-FIX 9.0 的 3 Important + 4 Minor 全采纳修订；3 项 slimapi 侧待确认事项（messageID 透传[权威核验点] / Partial+N==0 / sessions 错误深度）反向输出。
