# 实现规格 · 服务器分组重写 + 缓存/日志 UI 重组 + 命令超时/workdir 修复

> 评审对象。本文 + 实际 diff 共同构成"修改方案/处理方法/代码"。
> 源自 2026-07-07 grilling 共识。所有决策已与用户对齐。
> **改动校验**：每改完一项跑 `./scripts/check.sh`（LSP 已关，这是唯一编译反馈）。
> **设备纪律**：UI/集成测试仅用模拟器（`./scripts/emulator.sh status` 确认空闲再 start，用完 stop），不碰真机。

---

## 全局：新增字符串（集中由主链车道写入 `res/values/strings.xml`）

| key | 文案（zh，最终交设计师润色） | 用于 |
|---|---|---|
| `host_group_label` | `服务器分组` | 编辑器分组选择器标题 |
| `host_group_none` | `不分组` | 选择器选项 |
| `host_group_warning` | `同组 profile 共享：聊天历史、草稿、模型偏好、最近目录。选错组会导致这些数据串台或搁浅，仅可用「清除全部数据」恢复。` | 选了具名组时显示 |
| `group_stats_named` | `%1$s · %2$d 个连接 · %3$d 条缓存会话` | ConnectionProfileSection 统计行（具名组） |
| `group_stats_solo` | `独立 · 1 个连接 · %1$d 条缓存会话` | 统计行（不分组） |
| `cache_management_popup_title` | `缓存与维护` | 弹窗标题 |
| `cache_management_sweep_all` | `扫所有分组` | 弹窗顶部主按钮 |
| `command_submitted_processing` | `命令已提交，处理中…` | 项4 超时非致命提示（由 item4 车道引用，主链负责建条目） |
| `workdir_disconnect_title` | `断开此项目？` | 项5 断开确认弹窗 |
| `workdir_disconnect_message` | `将从列表移除并清除该项目的本地缓存，无法恢复。` | 同上 |
| `workdir_disconnect_confirm` | `断开` | 同上 |
| `workdir_empty_placeholder` | `无活跃会话，点此新建` | 项5 0-live workdir 占位 |

> 英文 `res/values/strings.xml` 同步加；`res/values-zh` 若存在则加中文（默认 values 放英文为 canonical，zh 放中文——按仓库现行约定，主链车道读现有 strings.xml 决定哪个是 default）。

---

## 项 1 · 服务器分组改人工维护（5 取值：不分组/A/B/C/D）

### 决策
- `serverGroupFp` 取值收敛为 `<profile.id>`（=不分组）或 `"A"/"B"/"C"/"D"`（具名槽位，全局固定 4 个 + 不分组）。
- 改组 = **只改 fp 指针，不搬缓存**（删所有 rekey 机器）。
- **软迁移**：fp∉{A,B,C,D} 即按读解释为"不分组"；不批量重写、不动 Room schema（列仍是 string）。
- **软迁移语义澄清（grilling Q3 有意决策，2026-07-07 评审 D1 复核后确认）**：「按读解释为不分组」是**UI 标签层**的解释（编辑器选择器把 UUID fp 显示为"不分组"），**不是**在缓存/配置键层把有效 fp 归一化为 `profile.id`。因此：当年"自动合并"遗留的幽灵链接（两个 profile 共享一个 UUID fp）**保留**——这是当时同服务器合并的正确态，保留=不回归；用户主动改组即可断开。代码各处 fp 派生仍用 `serverGroupFp.ifBlank { id }`，**不引入** effective-fp 归一化 helper。已知可接受边角：遗留幽灵链接下统计行 `group_stats_solo` 硬编码"1 connection"可能低于实际 `profilesInGroup(fp).size`，属软迁移可接受范围。
- 删自动合并、删 merge/split UI。

### 改动（逐文件）

**`data/model/HostProfile.kt`**
- 更新 `serverGroupFp` 字段 KDoc：说明取值语义（5 种），"不分组"=`<id>`，具名=label；同 fp 共享缓存/草稿/模型偏好/workdir/sweep-epoch。
- `normalizeGroupFp()`（L168-169）保持：blank→id。不动逻辑。

**`data/repository/HostProfileStore.kt`**
- **删** `mergeServerGroup(from, into)`（L250-260）。
- **删** `splitProfileToOwnGroup(profileId)`（L272-282）。
- **保留** `profilesInGroup(fp)`（L291-294）——项2 统计要用。
- 删上述两函数的所有内部调用点。

**`ui/settings/HostProfilesManagerScreen.kt` → `HostProfileEditorDialog`（L258-554）**
- 新增"服务器分组"选择器（5 选 1：不分组/A/B/C/D）。状态：
  ```kotlin
  val groupLabels = listOf("A","B","C","D")
  // 初始：fp ∈ groupLabels → 该 label；否则 → 不分组
  var selectedGroup: String? by remember(profile.serverGroupFp) {
      mutableStateOf(if (profile.serverGroupFp in groupLabels) profile.serverGroupFp else null)
  }
  var initialGroup = selectedGroup  // 记录初值，判断是否被动过
  ```
- UI：一行 5 个 segmented chip（不分组 / A / B / C / D）。选了具名组时，下方显示 `host_group_warning` 文案。
- **保存时 fp 解析**（只在选择被改动时才写 fp，避免误碰编辑器就拆幽灵链接）：
  ```kotlin
  val resolvedFp = if (selectedGroup != initialGroup) {
      selectedGroup ?: profile.id  // 不分组→自己 id；具名→label
  } else {
      profile.serverGroupFp  // 未改动，保留现状（含软迁移 UUID）
  }
  ```
  新建 profile 默认 `selectedGroup=null` → fp=id。
- 视觉摆位/配色交设计师，但数据契约如上。

**`ui/SessionListActions.kt`**
- **删** `attemptCrossGroupMerge(...)`（L495-542）及其调用点（loadSessions 流程里）。

**`data/cache/CacheRepository.kt`**
- **删** `mergeCacheGroup(fromFp, intoFp)`（L877-890）。
- 保留 `evictGroup(fp)`（L526-532）、`listGroupSessions(fp)`（L846-873）。

**`util/SettingsManager.kt`**
- **删** `copyPerFpConfig(...)`（L487）。
- 保留 `clearModelDataForGroup(fp)`（L473-478）。

**`ui/settings/CacheManagementSection.kt`**
- **删** per-group 的 merge/split 按钮（split 按钮在 `CacheGroupCard` 内，merge 相关入口）。
- 注意：项3 会把整块搬进弹窗；本项先把这些按钮连同其回调删干净。

**`res/values/strings.xml`**（+ zh 若有）
- 加 `host_group_label` / `host_group_none` / `host_group_warning`。

### 验证
- `./scripts/check.sh` 通过。
- 模拟器：建 profile→默认不分组；编辑器选 A→保存→fp=A；选不分组→fp=自己id；不改动保存→fp 不变。

---

## 项 5 · workdir 持久语义 + 僵尸修复 + 断开清缓存

### 决策
- "已连接的项目" = `recent_workdirs_<fp>` 里记着的 workdir（0-live 也留），上限 30（MRU）。
- 修僵尸：SSE 归档路径补更新 `directorySessions`。
- 断开 = 持久移出 `recent_workdirs` + `evictWorkdirInGroup` 清该 workdir 缓存 + 二次确认。重连=新建 session（不恢复旧数据）。

### 改动

**`util/SettingsManager.kt`**
- `addRecentWorkdir(fp, wd)`（L157-165）：加 MRU 上限 30——插入头部、去重、超 30 截断尾部。
- **新增** `removeRecentWorkdir(fp, wd)`：从列表移除该 wd。
- `getRecentWorkdirs(fp)` 不变。

**`ui/sessions/SessionsScreen.kt`**
- `workdirGroups` 派生（L137-185）：在现有 live-session 分组之后，**并入 `recent_workdirs_<fp>` 兜底**——凡在 recent 里但当前 0 live session 的 wd，补一个空 workdir 节点（占位 `workdir_empty_placeholder`）。`draftWorkdir` 特例（L171-176）泛化进此规则，删除特例分支。
  - recent_workdirs 来源：经 SettingsViewModel 暴露 `recentWorkdirs: StateFlow<List<String>>`（active fp）。
- 断开处理（现 `hiddenWorkdirs` 本地 state，L91）：**替换**为：
  ```kotlin
  // 长按 workdir 表头 → 弹确认对话框（workdir_disconnect_*）
  // 确认后：
  vm.disconnectWorkdir(wd)  // → settingsManager.removeRecentWorkdir(fp, wd) + cacheRepository.evictWorkdirInGroup(fp, wd)
  ```
  删除 `hiddenWorkdirs` 本地 state 及其用法。
- 一个 workdir 节点 0-live 时：表头 + 一个可点击的占位行（点击=在该 wd 新建 session）。

**`ui/controller/SessionSyncCoordinator.kt`**
- `applyArchiveEviction`（约 L446/449，由 L426-462 SSE 归档路径调用）：**对齐用户主动归档路径**——除更新 `sessions` 外，**同时更新 `directorySessions`**（用归档版本替换、或从对应 wd 列表移除该条），使 UI 的 `isArchived` 过滤能抓到。
  - 参照 `SessionMutationActions.kt:88-155` 用户主动归档对两个 map 的处理，保持一致。

**`ui/SettingsViewModel.kt`**
- 暴露 `recentWorkdirs`（active fp）给 SessionsScreen（若 SessionsScreen 不经 SettingsVM，则经其现用 VM——主链车道按实际接线定）。
- 加 `disconnectWorkdir(wd)` 方法。

**`res/values/strings.xml`**（+ zh）
- 加 `workdir_disconnect_title/message/confirm`、`workdir_empty_placeholder`。

### 验证
- 模拟器：归档某 workdir 唯一 session → workdir 仍留（空占位）；断开 → 弹确认 → 确认后从列表消失、重启 App 仍消失；在该 wd 新建 session → 重新出现（无旧历史）。
- 僵尸：触发 SSE 归档（多端/服务端归档）→ 该 session 不再以"活跃"赖在列表。

---

## 项 2 · 日志查看拆减 + 统计迁移到 Connections 区

### 决策
- 拆 `EffectBusDroppedPanel` + `SseUnknownEventsPanel`；留 `DebugLogSection` 查看器本体（全 tag）；留 `DebugLog` object+各处调用（转 Logcat）。
- 迁统计：① `profilesInGroup(fp).size` ② `listGroupSessions(fp).size`，只看 active fp。
- 扩 `ConnectionProfileSection` 加统计行。

### 改动

**`ui/settings/DebugLogSection.kt`**
- **删** `EffectBusDroppedPanel`（L122 调用 + L359-383 定义）。
- **删** `SseUnknownEventsPanel`（L124 调用 + 其定义）。
- 删两面板依赖的轮询（L108-116 effectBus.droppedEffectCount 轮询可留可删——若仅这两面板用则删；`unknownEventCounts` 轮询同理）。
- 保留日志列表/level 过滤/暂停/复制/清空。

**`ui/settings/SettingsSections.kt` → `ConnectionProfileSection`（L50-115）**
- 在 URL 行下加一条统计行，按 active fp 渲染：
  - 具名组：`group_stats_named`（组名/连接数/缓存会话数）
  - 不分组：`group_stats_solo`（缓存会话数）
- 统计行**可点击**（项3 入口）——加 `onClick` 回调参数，由 SettingsScreen 注入（打开缓存维护弹窗）。

**`ui/SettingsViewModel.kt`**
- 暴露 `activeGroupProfileCount: StateFlow<Int>` 与 `activeGroupCachedSessionCount: StateFlow<Int>`（active fp；用 `profilesInGroup` + `listGroupSessions`）。

**`res/values/strings.xml`**（+ zh）
- 加 `group_stats_named` / `group_stats_solo`。

### 验证
- `./scripts/check.sh`；设置页 Connections 区显示统计行；Debug 区不再有两个诊断面板，日志查看器仍在。

---

## 项 3 · 扫除等功能进独立弹窗

### 决策
- `CacheManagementSection` 整块搬进 modal 弹窗；settings 主滚动区移除该块。
- 入口 = ConnectionProfileSection 统计行可点击 → 开弹窗。
- "Clear all" 保留"清所有分组缓存"原义（只清 Room 聊天缓存；≠ DangerZone 的清除全部数据）。
- 弹窗展示全部分组 + 顶部"扫所有分组"主按钮；每组各有"立即扫孤儿"。
- 后台自动扫除（SSE 连上时 `dailySweepIfNeeded`）保留；手动按钮绕过 24h 去重。
- Debug section 最小重塑：留 header + DebugLogSection + DangerZone；DebugLog 始终可见。

### 改动

**`ui/settings/CacheManagementSection.kt`**
- 整体改为弹窗内容：保留按组 LazyColumn / 每组"立即扫孤儿" / clear-session / clear-project / 疑弃用检测 / SweepResultLine。
- 顶部加"扫所有分组"主按钮：遍历所有现存 fp（profilesInGroup 反查或遍历 profiles 的 distinct fp），逐个 `sweepNow(fp)`。
- 保留"Clear all"（调 `cacheRepository.clearAll()`，原义）。

**`ui/settings/SettingsScreen.kt`**
- 主滚动区**移除**内嵌 `CacheManagementSection(...)` 调用（L183 附近）。
- 加弹窗 state（`showCacheDialog`），由 ConnectionProfileSection 统计行 `onClick` 触发。
- 弹窗用 `Dialog`/`AlertDialog` 包 `CacheManagementSection`（hideHeader=true，标题用 `cache_management_popup_title`）。
- DangerZone/DebugLog 位置不动。

**`res/values/strings.xml`**（+ zh）
- 加 `cache_management_popup_title` / `cache_management_sweep_all`。

### 验证
- `./scripts/check.sh`；统计行点击→弹窗弹出、列全部分组、能逐组/全量扫孤儿；Clear all 仅清缓存（profile/草稿/模型偏好仍在）；后台连上仍自动扫。

---

## 项 4 · 命令超时修复（独立车道，文件不与主链重叠）

### 决策
- `executeCommand`（`POST /session/{id}/command`）专用 OkHttp client，`readTimeout=300s`。
- 命令 POST 超时 = **非致命**：捕获 `SocketTimeoutException` → 中性提示 `command_submitted_processing`，让 SSE 接力；真正 HTTP 4xx/5xx 仍报 `error_command_failed`。
- `prompt_async` 不动。
- 注意：**不写 strings.xml**（`command_submitted_processing` 由主链车道建条目）；本车道只引用 `R.string.command_submitted_processing`。

### 改动（本车道独占）

**`data/api/OkHttpClientFactory.kt`**
- 新增 `commandClient`：`baseBuilder.readTimeout(300, TimeUnit.SECONDS).build()`（继承 baseBuilder 的 connectTimeout 10s 与所有拦截器/auth）。
- 暴露 `commandClient`（与 `restClient`/`sseClient` 同级）。

**`data/api/OpenCodeApi.kt`**
- **不动接口定义**（`executeCommand` 签名不变）。

**`data/repository/OpenCodeRepository.kt`**
- 构建一个 `commandApi`：新 Retrofit 实例（同 baseUrl / 同 JSON converter / 同 auth 拦截器 / 用 `commandClient`），承载 `executeCommand`。
- `executeCommand(...)` 改走 `commandApi.executeCommand(...)`（其余方法仍走原 `api`）。
- DI/构造：若 repository 由 DI 提供 OkHttpClient，把 `commandClient` 一并注入；若内部自建工厂，调工厂取 `commandClient`。

**`ui/AppCoreOrchestration.kt`（`executeCommand`，L86-135）**
- 失败处理改：
  ```kotlin
  .onFailure { error ->
      if (error is java.net.SocketTimeoutException) {
          // POST 等 ACK 超时，但 SSE 会送结果——非致命
          effectBus.tryEmitUiEvent(UiEvent.Info(R.string.command_submitted_processing))
      } else {
          effectBus.tryEmitUiEvent(UiEvent.Error(R.string.error_command_failed,
              listOf(cmd, errorMessageOrFallback(error, "unknown error"))))
      }
  }
  ```
- 若 `UiEvent` 无 `Info` 变体：新增 `UiEvent.Info(messageRes: Int, args: List<String> = emptyList())`，并在 `ChatScreen.kt`（L456-481 附近 UiEvent.Error 收集处）加 `is UiEvent.Info ->` 分支，用同样 `showTimed`（短时长、非红色）展示。若新增 Info 变体侵入太大，退路=超时时静默不提示（依赖 SSE 自然更新 UI），但**优先**实现 Info 提示。

### 验证（模拟器，必做）
- `./scripts/check.sh`。
- 模拟器跑一条慢命令（如 `/compact` 或加载重 skill）；`adb logcat | grep SSE` 确认 POST 最终是 200 还是挂死；
  - 若 200（慢但回）→ 300s 够、不再误报超时。
  - 若挂死 → 非致命提示弹出、SSE 仍送结果、UI 正常更新。

---

## 依赖与车道

```
主链（fix-gpt，串行， owns UI/data 文件 + strings.xml）：项1 → 项5 → 项2 → 项3
项4（fixer，并行， owns OkHttpClientFactory/OpenCodeApi/OpenCodeRepository/AppCoreOrchestration[+可能 UiEvent/ChatScreen]）
```
两车道文件无重叠（项4 不碰 strings.xml / Settings* / Cache* / Session*）。两车道完成后 orchestrator 跑一次 `./scripts/check.sh` 汇总校验。

## 不在本次范围
- 不手改 `app/build.gradle.kts`（版本号走 release.sh）。
- 不删 `DebugLog` object 与各处 `DebugLog.*` 调用。
- 不动 `prompt_async` / 普通发消息路径。
- 不动 Room schema（无数据库迁移）。
