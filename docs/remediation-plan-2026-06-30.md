# opencode-android 问题修复计划（2026-06-30）

> 基于 6 路并行评审（glm/dsf/momo/max × 3 组）整理。所有阻塞项已由编排者逐一核实源码确认存在。
> 用户决策已并入：
> - **TLS trustAll 属设计意图**：改为按 host 配置的"接受不安全连接"开关（默认关闭），降级为受控的局部服务器便利项。
> - **FilesScreen 清空 semantics 属设计意图**（个人专用程序）：撤回该阻塞项。

---

## P0 — 上线前必修（崩溃/ANR/安全受控化）

### R-01 [重构] TLS 受控化：trustAll 改为按 host 的"接受不安全连接"开关
- **原问题位置**：`data/repository/OpenCodeRepository.kt:79,126-127,306-310,368-369,693-694`（三处全局安装 `trustAllSslSocketFactory` + `HostnameVerifier{_,_->true}`）
- **影响**：当前对所有 host 默认 trustAll。改为受控后：默认走系统证书校验；用户在 host profile 显式勾选"接受不安全连接"才对该 host 放开，并在 UI 标注警告。
- **修复方案**：
  1. `HostProfile` 新增 `allowInsecureConnections: Boolean = false`。
  2. `SettingsManager` 持久化该字段；`SettingsScreen` 服务器配置区提供开关 + 警告文案。
  3. 抽统一 SSL 工厂：默认返回系统 TrustManager；仅当 `profile.allowInsecureConnections == true` 时对该 client 装 trustAll。
  4. 三处（`buildOkHttpClient`/`buildTunnelOkHttpClient`/`checkHealthFor`）统一调用工厂。
- **关键代码**：
  ```kotlin
  // data/repository/OpenCodeRepository.kt
  private fun sslConfig(profile: HostProfile): SslConfig {
      if (!profile.allowInsecureConnections) return SslConfig.SystemDefault
      return SslConfig.TrustAll(trustAllTrustManager, trustAllSslSocketFactory())
  }

  private fun OkHttpClient.Builder.applySsl(cfg: SslConfig) = when (cfg) {
      SslConfig.SystemDefault -> this
      is SslConfig.TrustAll -> sslSocketFactory(cfg.factory, cfg.tm)
          .hostnameVerifier { _, _ -> true }
  }

  sealed interface SslConfig {
      data object SystemDefault : SslConfig
      data class TrustAll(val tm: X509TrustManager, val factory: SSLSocketFactory) : SslConfig
  }
  ```
  ```kotlin
  // ui/settings/SettingsScreen.kt — 服务器配置区
  Switch(
      checked = profile.allowInsecureConnections,
      onCheckedChange = { vm.setAllowInsecure(profile.id, it) }
  )
  if (profile.allowInsecureConnections) {
    Text(stringResource(R.string.host_insecure_warning),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall)
  }
  ```

### R-02 图片主线程同步解码（基线 H3 未修）
- **位置**：`ui/files/FilePreviewPane.kt:87-88,321-338`；`ui/util/DataUriImageTransformer.kt:91-106`；`ui/chat/ChatInputBar.kt:342-344`（缩略图，影响小）
- **影响**：MB 级图片冻结主线程 → 掉帧/ANR；超大图 OOM。
- **修复方案**：解码移 `Dispatchers.Default`；`inJustDecodeBounds` 探尺寸后按目标分辨率算 `inSampleSize` 采样；`produceState` 先 null 后异步填充（加 skeleton）。
- **关键代码**：
  ```kotlin
  // FilePreviewPane.kt
  val imagePayload by produceState<DecodedImagePayload?>(null, path, content) {
      value = null
      value = withContext(Dispatchers.Default) { decodeImagePayloadSampled(content, targetPx = 2048) }
  }

  private fun decodeImagePayloadSampled(raw: String, targetPx: Int): DecodedImagePayload? {
      val bytes = Base64.decode(raw.substringAfter(","), Base64.DEFAULT)
      val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
      opts.inSampleSize = calcInSampleSize(opts.outWidth, opts.outHeight, targetPx)
      opts.inJustDecodeBounds = false
      val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
      return DecodedImagePayload(bytes, bmp)
  }
  private fun calcInSampleSize(w: Int, h: Int, target: Int): Int {
      var s = 1
      while ((w / s) * (h / s) > target * target) s *= 2
      return s
  }
  ```

### R-03 `HttpImageHolder.cachedBitmaps` 内存缓存无界（基线 H2 残留）
- **位置**：`ui/util/DataUriImageTransformer.kt:133`
- **影响**：浏览大量不同图片会持续吃内存直至 OOM。
- **修复方案**：换 `LruCache`（按 `Bitmap.byteCount` 计量），`onTrimMemory` 清理。
- **关键代码**：
  ```kotlin
  private val cachedBitmaps = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
      override fun sizeOf(key: String, value: Bitmap) = value.byteCount
  }
  // 切会话/低内存时：cachedBitmaps.evictAll()
  // 读取由 cachedBitmaps[url] = bitmap 替换 mutableStateMapOf 赋值；UI 侧用 derivedStateOf 或 produceState 观察命中
  ```

### R-04 REST 客户端 `readTimeout(0)` 无限挂起（M1 未修）
- **位置**：`data/repository/OpenCodeRepository.kt:302`
- **影响**：慢响应/死连接耗尽连接池（OkHttp 64 连接 × 5min idle ≈ 32MB）。
- **修复方案**：拆 REST(30s)/SSE(0) 两个 client，SSE 走专用 client。
- **关键代码**：
  ```kotlin
  private val restHttp: OkHttpClient = baseBuilder()
      .readTimeout(30, TimeUnit.SECONDS).build()
  private fun sseHttp(): OkHttpClient = baseBuilder()
      .readTimeout(0, TimeUnit.SECONDS).build()
  // Repository 内 REST 调用用 restHttp；connectSSE 传 sseHttp()
  ```

### R-05 WebView 无 `onRelease`（M2 未修）
- **位置**：`ui/files/MarkdownWebPreviewPane.kt:158`
- **影响**：每次预览累积 5-30MB native 内存。
- **修复方案**：加 `onRelease`。
- **关键代码**：
  ```kotlin
  AndroidView(
      factory = { ctx -> WebView(ctx).apply { /* ... */ } },
      update = { /* ... */ },
      onRelease = { w ->
          w.stopLoading(); w.settings.javaScriptEnabled = false
          w.loadUrl("about:blank"); w.destroy()
      }
  )
  ```

### R-06 `warmedWebView` 永不销毁（M6 未修）
- **位置**：`OpenCodeApp.kt:38-52`
- **影响**：Application onCreate 创建后静态 hold 不释放不复用。
- **修复方案**：直接删除 `warmUpWebViewAfterLaunch()` 与 `warmedWebView` 字段（冷启动 ~50ms 可接受，配合 R-05）。

---

## P1 — 紧随其后（合规/分层/无障碍/并发卫生）

### R-07 release OkHttp BASIC 日志未门控（M4 未修）
- **位置**：`data/repository/OpenCodeRepository.kt:134`
- **修复**：
  ```kotlin
  level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
          else HttpLoggingInterceptor.Level.NONE
  ```

### R-08 `usesCleartextTraffic="true"` 冗余且误导（H4 残留）
- **位置**：`AndroidManifest.xml:22`
- **修复**：删除该行（network_security_config 已收紧 base）。`ts.net` 明文豁免过宽（`includeSubdomains=true`），建议收窄或加用户确认。

### R-09 SSEClient 反向依赖 UI 层（分层违规）
- **位置**：`data/api/SSEClient.kt:4`（`import ...ui.NOISY_SSE_LOG_EVENTS`）
- **修复**：把 `NOISY_SSE_LOG_EVENTS` 下沉到 `data/api/` 或新增 `data/SseLogFilter.kt`，UI 反向 import。

### R-10 SettingsManager 反向依赖 UI theme 层（分层违规）
- **位置**：`util/SettingsManager.kt:9`（`import ...ui.theme.MarkdownFontSizes`）
- **修复**：`MarkdownFontSizes` 下沉到 `util/` 或 `data/model/`，`ui.theme` 反向 import。

### R-11 Theme.kt 绕过 Hilt 构造第二个 SettingsManager
- **位置**：`ui/theme/Theme.kt:159`
- **修复**：通过 `@EntryPoint` 将 `SettingsManager` 暴露给 Composable，或字体状态 hoist 到 MainViewModel 下传。

### R-12 触控目标 < 48dp（违反 M3/WCAG 2.5.5）
- **位置**：`ChatInputBar.kt:211,470`(28dp 发送/附件)、`ChatTopBar.kt:544`(20dp 关闭X)、`ChatInputBar.kt:363`(24dp 图片删除)、`ChatTopBar.kt:924`(28dp contextRing)、`SessionsScreen.kt:273,294`(32dp)
- **修复**：触控区扩到 ≥44dp，图标视觉尺寸不变。
- **关键代码**：
  ```kotlin
  Box(
      modifier = Modifier
          .minimumInteractiveComponentSize()  // 自动补足 48dp
          .size(28.dp)
          .clickable { /* ... */ },
      contentAlignment = Alignment.Center
  ) { Icon(Icons.Default.Send, null, modifier = Modifier.size(22.dp)) }
  ```

### R-13 深色模式启动白闪（无 `values-night/colors.xml`）
- **位置**：`values/colors.xml:10` `window_background=#FFFFFFFF`
- **修复**：新增 `values-night/colors.xml` 定义 `window_background=#FF080808`（对齐 `DarkBackground`）。

### R-14 `catch(_: Exception)` 吞 CancellationException（协程禁忌）
- **位置**：`MainViewModel.kt:1359`、`MainViewModelSessionActions.kt:423`、`MainViewModelSyncActions.kt:364`（至少 3 处在协程上下文）
- **修复**：
  ```kotlin
  } catch (e: Exception) {
      if (e is CancellationException) throw e
      // 原有处理
  }
  ```

### R-15 `DataUriImageTransformer` hashCode() 作缓存键（碰撞风险）
- **位置**：`ui/util/DataUriImageTransformer.kt:78`
- **修复**：直接用 `link` 全量，或 SHA-256 摘要。注意 `:154` 的 `url.hashCode()` 用作磁盘文件名同此问题。

---

## P2 — 架构重构（1-2 sprint）

### R-16 MainViewModel 上帝类（~3.9K 行/5 文件）
- **影响**：拆分仅切行数未切职责，新增功能必触此聚合根，合并冲突概率高；测试已用反射访问私有 `_state`（可测性恶化信号）。
- **修复方案**：抽独立类
  - `SessionSyncCoordinator`（SSE 事件 → AppState 折叠）
  - `ForegroundCatchUpController`（三档阈值 + sseHasConnectedOnce/suppressNextConnectCatchUp 状态机）
  - `HostProfileController` / `ConnectionController`
  - `selectSession`（130+ 行/8 职责）拆 `SessionSwitcher`：`captureOutgoingSnapshot`/`restoreIncomingSnapshot`/`updateOpenTabs`/`updateUnreadMachine`

### R-17 AppState 44 字段单 StateFlow → 全局重组
- **修复方案**：拆多个 `MutableStateFlow`（`connectionFlow`/`chatFlow`/`sessionListFlow`/`fileFlow`/`settingsFlow`/`composerFlow`），AppState 退化为派生快照。`ChatTopBarState` 20+ 字段同理拆 5 个小组件。

### R-18 OpenCodeRepository 766 行/8 职责
- **修复方案**：抽 `HttpInterceptors`（每个 interceptor 一对象）、`HostConfig`、`SSEFactory`、`OkHttpConfig` 工厂；`buildOkHttpClient` 180+ 行仅编排。统一三处 SSL 配置（见 R-01 工厂）。

### R-19 Hilt 冗余 `@Provides`
- **位置**：`di/AppModule.kt:17-28`（OpenCodeRepository/TrafficLogger 已 `@Inject`）
- **修复**：删除冗余 `@Provides`。

### R-20 错误处理策略不统一（4 风格混用）
- **修复方案**：写 `docs/error-handling-policy.md`；抽 `AppErrorHandler`（reportFatal/reportNonFatal 二分）；静默 catch 仅限 best-effort progressive enhancement。

### R-21 SSEClient 零测试（核心组件）
- **修复方案**：用 MockWebServer SSE 模式覆盖重连/退避/MAX_RETRY 耗尽/心跳超时/URL 脱敏。
- 同步补 `SettingsManager.clearAllLocalData` 保留键白名单、`AppLifecycleMonitor` 通知去重状态机、图片缓存层测试。

### R-22 kover 无覆盖率门槛
- **修复**：加 `koverVerify` 阈值并接入构建失败门。

---

## P3 — UI 系统化整改

### R-23 60+ 处字符串硬编码
- **重点**：调试日志区 11 处中文、聊天核心 20+ 处中英、ViewModel 业务层 8 处中文错误消息；**恶劣项**：已有 `R.string.chat_tool_calls`/`chat_files_changed` 却仍写字面量。
- **修复**：脚本辅助 grep `Text("`/`contentDescription = "`，全部抽 `strings.xml`；ViewModel 错误消息改 sealed class/enum 由 UI 按 locale 解析。

### R-24 ChatTopBar 状态色硬编码（项目已有 token 未用）
- **位置**：`ChatTopBar.kt:332-335`（`0xFFFFA500/0xFF4CAF50/0xFFF44336`）
- **修复**：用已有 `oc.stateSuccessFg`/`oc.stateInfoFg`/`oc.stateDangerFg`。

### R-25 新增 Shape.kt + Dimens.kt
- 现状：33+ 处 `RoundedCornerShape(4/6/8/10/12.dp)` 散落。
- **修复**：定义 `val cardShape = RoundedCornerShape(6.dp)` 等，通过 `MaterialTheme(shapes=...)` 下发；间距抽 `Dimens`。

### R-26 XML 主题改用 Material3
- **位置**：`res/values/themes.xml:4`（parent `Theme.AppCompat.DayNight.NoActionBar`）
- **修复**：改 `Theme.Material3.DayNight.NoActionBar`；删除 colors.xml 残留 M2 紫色/teal。

### R-27 AgentTone / git diff 语义色深色模式适配
- **修复**：移到 `OpencodeColors`，明暗双套；建议跑 WCAG 对比度校验（AgentTone 16 色块当前未做明暗适配）。

### R-28 "¥" 货币符号 + contentDescription 国际化
- **位置**：`ChatTopBar.kt:673`（"¥"）；多处 `contentDescription` null/硬编码英文
- **修复**：抽 `stringResource`，含变量用 `%1$s`/`%1$d` 占位；`NumberFormat.getCurrencyInstance(locale)`。

### R-29 引入 Coil 替换手写图片加载（可选，收益大）
- 现状：`DataUriImageTransformer`/`HttpImageHolder` 手写 LruCache + inflight 去重 ~200 行。
- **修复**：Coil 2.x `AsyncImage` 自带三级缓存/请求去重/inSampleSize/协程友好。

---

## 撤回项（用户决策）

- ~~FilesScreen 清空 semantics~~：个人专用程序，按设计撤回。
- TLS trustAll：降级为受控的 host 配置项（R-01），非安全漏洞。

## 待用户决策项

1. **DynamicColor**：故意不支持（品牌一致性），是否在 Settings 加可选"跟随系统壁纸"开关？
2. **`ts.net` 明文豁免**：是否收窄 `includeSubdomains` 范围或加用户确认？
3. **基线审计更正**：是否产出 `docs/security-audit-2026-06-30.md` 增量报告（AGENTS.md 规定同步后应出增量审计，当前 docs/ 仅 1 份基线）？

---

## 实施进度与定稿（2026-06-30）

> 本章是两条修复 lane（data-core / ui）+ 跨层收尾合并入 `dev` 后的终态记录，
> 并入两审反馈（gpter 7/9 + glmer 6.7/9 终审）与用户决策。

### 1. 已实施项（已在 `dev` 分支）

#### data-core lane（✅ 已实施）
- **R-01** TLS 受控化：`HostProfile.allowInsecureConnections` + Repository 统一
  `sslConfigFor`/`applySsl` 工厂；`configure`/`checkHealthFor`/`activateTunnel`
  均带 `allowInsecure(Connections)` 参数（默认 false）。UI 4 处调用点已 wire
  profile 字段（见本 agent 跨层收尾）。
- **R-04** REST 客户端 `readTimeout(0)` 拆分：REST 30s / SSE 0s 双 client。
- **R-07** release OkHttp BASIC 日志门控（`BuildConfig.DEBUG`）。
- **R-08** `usesCleartextTraffic` 冗余清理 + network_security_config 收紧。
- **R-14（repo 侧）** Repository 全量 `runCatching` → `runSuspendCatching`
  （正确 rethrow `CancellationException`）。
- **R-19** Hilt 冗余 `@Provides` 清理（`OpenCodeRepository` 已 `@Inject`）。

#### ui lane（✅ 已实施）
- **R-02** 图片主线程同步解码：`produceState` + `Dispatchers.Default` 异步解码
  （`FilePreviewPane` / `DataUriImageTransformer` / `ChatInputBar`）。
- **R-05** WebView `onRelease`（`MarkdownWebPreviewPane`）。
- **R-06** 删除 `warmedWebView` 永不销毁的静态 hold（`OpenCodeApp`）。
- **R-12** 触控目标 < 48dp 扩补（`minimumInteractiveComponentSize`）。
- **R-13** 深色模式启动白闪：新增 `values-night/colors.xml`
  （`window_background=#FF080808`）。
- **R-24** ChatTopBar 状态色硬编码改用 `oc.*Fg` token。
- **R-28** "¥" 货币符号 + contentDescription 抽 `stringResource`（含占位符）。
- **R-14（ui 侧 #2）** `MainViewModelSessionActions.kt:423` rethrow CE。

#### 跨层收尾（本 agent，✅ 已实施）
- **R-09** SSEClient 反向依赖 UI 层 → `NOISY_SSE_LOG_EVENTS` 下沉到
  `data/api/SseLogFilter.kt`；UI 侧（`MainViewModelSyncActions`）正向 import。
- **R-10** SettingsManager 反向依赖 UI theme 层 → `MarkdownFontSizes` 下沉到
  `util/MarkdownFontSizes.kt`；`ui.theme`/`ui.MainViewModel`/`MarkdownWebPreviewPane`
  正向 import util。
- **R-11** Theme.kt 第二个 `SettingsManager` → 改用 Hilt `@EntryPoint`
  （`SettingsManagerEntryPoint` + `rememberSettingsManager()`）；`SettingsManager`
  本身已是 `@Singleton @Inject constructor`，无需改 `AppModule`。
- **R-15** `DataUriImageTransformer` hashCode() 缓存键：内存键改用完整 `link`，
  磁盘文件名改用 SHA-256（截 32 hex）。

### 2. 两审反馈并入（gpter 7/9 + glmer 6.7/9 终审）

- **R-26 降级方案**：经评审 ROI 评估，**保留 AppCompat parent**（不引入
  Material Components 依赖），仅清理 `colors.xml` 残留 M2 紫色/teal +
  `window_background`。完整 M3 XML 主题迁移因依赖切换成本高、收益有限而搁置。
- **R-28 固定 CNY**：用户确认 `Message.cost` 固定显示人民币（在 opencode 填的
  数值即 CNY），不跟随 locale。详见 §6。
- **R-14 定位修正**：编排者已源码核实 ——
  `MainViewModel.kt:1357-1359` **已正确 rethrow `CancellationException`**，非问题
  （原盘点为误报）；真问题为 `MainViewModelSessionActions.kt:423`（已修）+
  Repository `runCatching`（已由 `runSuspendCatching` 修）；
  `MainViewModelSyncActions.kt:364` 是**非 suspend 的 JSON decode**，豁免。
  同步更正已写入 `docs/rfc-r20-error-handling.md` §1.1 与盘点表 #3 行。

### 3. 依赖图（修复项之间的前置/联合关系）

```
R-09 (NOISY_SSE 下沉) ─┐
R-10 (MarkdownFontSizes 下沉) ─┴─► R-11 (Theme EntryPoint) ─► R-17 (AppState 拆分, RFC)
                                      └─► R-16 (VM 拆分, RFC) ──► R-20 (错误处理, RFC)

R-01 (TLS 工厂) ─┐
R-04 (REST/SSE 双 client) ─┼─► 联合触碰 buildOkHttpClient（已联合实施）
R-18 (Repository 拆, RFC) ─┘

R-14 (runSuspendCatching) ─► R-20 M1 前置
```

- **R-09 / R-10 是 R-11 / R-16 / R-17 的前置**：分层下沉后，DI EntryPoint 与
  后续 VM/AppState 拆分才有干净的依赖方向（data 不再依赖 ui）。
- **R-01 / R-04 / R-18 联合触碰 `buildOkHttpClient`**：已由 data-lane 联合实施
  （统一 SSL 工厂 + 双 client + 隧道 client），避免三处各自改 OkHttp 配置互相
  覆盖。
- **R-14 是 R-20 M1 前置**：`runSuspendCatching` 就绪后 `AppErrorHandler` 才能
  安全分发错误（CE 已被隔离）。

### 4. 22 色明暗双套清单（R-27）

> R-27 要求下列 22 个语义色在 `OpencodeColors` 中明暗双套（当前 AgentTone 16 色
> 未做明暗适配）。清单已核实，作为 R-27 实施的完整范围。

**AgentTone.kt — 16 色**（4 标准 + 12 palette）：
- 标准 4：`build` / `code` / `web` / `ask`（各 1 色）
- palette 12：tone1 ~ tone12

**Color.kt — 6 色**（git diff 语义色）：
- `AddedLine` / `DeletedLine` / `ModifiedFile` / `AddedFile` / `DeletedFile` /
  `UntrackedFile`

**合计 22 色**，均需在 `LightOpencodeColors` / `DarkOpencodeColors` 双套提供，
并跑 WCAG 对比度校验（建议 ≥ AA 4.5:1 正文 / 3:1 大字与图形）。

### 5. P2 RFC 立项

已起草三份 P2 架构重构 RFC：
- **R-16** `docs/rfc-r16-mainviewmodel-split.md`（MainViewModel 上帝类拆分）
- **R-17** `docs/rfc-r17-appstate-split.md`（AppState 44 字段单 Flow → 多 Flow 重组）
- **R-20** `docs/rfc-r20-error-handling.md`（统一错误处理策略）

**⚠️ 编排者注意的执行顺序冲突**（待用户决策）：
- **ora-1（R-16 评审）建议**：**R-16 先、R-17 后** —— 先拆 VM 职责到 Coordinator，
  再拆 AppState，错误处理点（R-20）随 Coordinator 各自落地。
- **ora-2（R-17 评审）建议**：**R-17 先、R-16 后** —— 先把 AppState 重组为多 Flow，
  VM 拆分时各 Coordinator 直接订阅子 Flow，避免拆完再大改状态管线。

两份 RFC 的执行顺序互斥，**列为待用户决策项**，需在开工前裁定。

### 6. CNY 货币决策

**决策**：`Message.cost`（聊天中模型花费展示）**固定显示人民币（¥）**，不跟随
locale。

**依据（用户确认）**：用户在 opencode 服务端填入的 cost 数值即人民币口径，客户端
按 locale 切换货币符号（如 `$`）会造成语义错误（数值未换算却换了符号）。因此
R-28 的货币符号固定为 `¥`，仅文案/`contentDescription` 走 `stringResource` 国际化。
