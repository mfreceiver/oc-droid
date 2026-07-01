# opencode Android WebView 套壳方案

> 日期：2026-06-28
> 目标：从零新建一个 Android 项目，用 WebView 包裹 opencode-web 前端，添加原生隧道/服务器配置/DOM裁剪。本文件为独立项目的完整技术方案。

---

## 1. 架构总览

```
┌──────────────────────────────────────────────────────────────┐
│  App Startup                                                 │
│  ├─ load current HostProfile (EncryptedSharedPreferences)     │
│  ├─ health probe: GET {serverUrl}/global/health               │
│  ├─ on failure → auto-activate tunnel → 2s delay → re-probe   │
│  └─ on success → load WebView                                 │
├──────────────────────────────────────────────────────────────┤
│  Main Activity (immersive fullscreen)                         │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  WebView                                               │  │
│  │  url = profile.serverUrl                               │  │
│  │  + JS injection: DOM prune rules on page load          │  │
│  │  + JS injection: force-dark-mode if needed             │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                      [⚙️]   │  │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  Overlay: loading / tunnel-activating / error           │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
                              │
        native BottomSheet ←─┘ (trigger: floating gear icon)
                              │
┌──────────────────────────────────────────────────────────────┐
│  Settings (native Compose BottomSheet / Activity)             │
│  · Server profiles (CRUD) + tunnel password                   │
│  · DOM prune rules (label + CSS selector list)                │
│  · Dark mode / appearance                                     │
└──────────────────────────────────────────────────────────────┘
```

**核心主张**：不做 native 聊天 UI。WebView 加载 opencode-server 自带的 web 前端（`{serverUrl}` 根路径即 SPA）。原生层只负责：服务器连接管理、隧道自动激活、DOM 裁剪注入、沉浸式全屏外壳。

---

## 2. 启动流程

### 2.1 数据模型

```kotlin
// 复用自现有项目 HostProfile.kt（无需修改）
@Serializable
data class HostProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    @SerialName("serverURL")
    val serverUrl: String,           // e.g. "https://my-opencode.example.com"
    val basicAuth: BasicAuthConfig? = null,  // HTTP Basic Auth (optional)
    val tunnelPasswordId: String? = null,    // tunnel credential ref
    val lastUsedAt: Long? = null
)

@Serializable
data class BasicAuthConfig(
    val username: String,
    val passwordId: String
)

// 复用自现有项目 Config.kt
@Serializable
data class HealthResponse(
    val healthy: Boolean,
    val version: String? = null
)
```

### 2.2 启动流水线

```
launchSequence(currentProfile):
  ├─ result = healthProbe(currentProfile.serverUrl, basicAuth)
  │       GET {url}/global/health
  │       超时 15s，信任所有证书（自签场景）
  │
  ├─ if result is HealthResponse(healthy=true):
  │     → loadWebView(currentProfile.serverUrl)
  │     → DONE
  │
  ├─ if result failed AND currentProfile.tunnelPasswordId != null:
  │     → show overlay "隧道激活中…"
  │     → tunnelPassword = settingsManager.getTunnelPassword(tunnelPasswordId)
  │     → activateTunnel(currentProfile.serverUrl, tunnelPassword)
  │            POST {url}
  │            Content-Type: application/x-www-form-urlencoded
  │            Body: persist_auth=off&pw={password}
  │     → delay(2000)
  │     → result2 = healthProbe(currentProfile.serverUrl, basicAuth)
  │     → if result2 ok: loadWebView(currentProfile.serverUrl)
  │     → else: show error overlay with "manual retry" button
  │
  └─ if result failed AND no tunnel password configured:
        → show error overlay "无法连接服务器" + "打开设置" button
```

### 2.3 Health Probe 实现

```kotlin
// 独立 OkHttpClient（信任所有证书 + 15s 超时）
suspend fun healthProbe(
    serverUrl: String,
    username: String?,
    password: String?
): Result<HealthResponse> = withContext(Dispatchers.IO) {
    runCatching {
        val client = OkHttpClient.Builder()
            .apply {
                val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                    override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                })
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAll, SecureRandom())
                sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
                hostnameVerifier { _, _ -> true }
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val url = (if (serverUrl.startsWith("http")) serverUrl else "http://$serverUrl")
            .trimEnd('/') + "/global/health"
        val request = Request.Builder()
            .url(url)
            .header("X-Opencode-Skip-Dir", "1")
            .apply {
                if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
                    val credential = "$username:$password"
                    header("Authorization", "Basic " +
                        Base64.getEncoder().encodeToString(credential.toByteArray()))
                }
            }
            .build()
        val response = client.newCall(request).execute()
        response.use { res ->
            if (!res.isSuccessful) throw Exception("HTTP ${res.code}")
            val body = res.body?.string().orEmpty()
            if (body.isBlank()) throw Exception("Empty response body")
            Json.decodeFromString<HealthResponse>(body)
        }
    }
}
```

### 2.4 Tunnel 激活实现

```kotlin
// POST form-encoded password to server URL
suspend fun activateTunnel(tunnelUrl: String, password: String): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            val client = OkHttpClient.Builder()
                // same trust-all TLS setup as health probe
                .sslSocketFactory(...)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(15, TimeUnit.SECONDS)
                .build()
            val formBody = FormBody.Builder()
                .add("persist_auth", "off")
                .add("pw", password)
                .build()
            val request = Request.Builder()
                .url(tunnelUrl)
                .post(formBody)
                .build()
            val response = client.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) {
                    val body = it.body?.string().orEmpty()
                    throw Exception("HTTP ${it.code}: ${body.ifBlank { "(空响应)" }}")
                }
            }
        }
    }
```

---

## 3. 满屏沉浸 + 设置入口

### 3.1 全屏

```kotlin
// 在 Activity.onCreate 中
WindowCompat.setDecorFitsSystemWindows(window, false)
window.insetsController?.apply {
    hide(WindowInsets.Type.systemBars())
    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
}
// 下拉可临时呼出状态栏和导航栏
```

### 3.2 浮动齿轮按钮

```kotlin
// 右下角半透明浮动按钮，3 秒无操作淡出至 20% 透明度
@Composable
fun FloatingSettingsButton(onClick: () -> Unit) {
    var visible by remember { mutableStateOf(true) }
    // Inactivity timer: reset on touch, fade after 3s
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            visible = false  // auto-fade
        }
    }
    AnimatedVisibility(
        visible = visible || recentlyTouched,
        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
    ) {
        FloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Icon(Icons.Default.Settings, "Settings")
        }
    }
}
```

### 3.3 设置页（原生 BottomSheet）

```
┌──────────────────────────────────────────┐
│  设置                            [关闭]   │
├──────────────────────────────────────────┤
│  ── 服务器 ──                             │
│  ┌──────────────────────────────────────┐│
│  │ ● My Server    https://oc.example.com ││  ← 点击切换 / 长按编辑
│  │ ○ Localhost    http://localhost:4096  ││
│  │ ＋ 添加                               ││
│  └──────────────────────────────────────┘│
│                                          │
│  编辑：                                   │
│   名称: [My Server          ]             │
│   URL:  [https://oc.example.com]          │
│   用户名(可选): [            ]             │
│   密码(可选):   [••••       ]              │
│   隧道密码:     [••••       ]              │
│   [测试连接]  [保存]                       │
│                                          │
│  ── 显示 ──                               │
│  DOM 裁剪规则:                             │
│  ┌──────────────────────────────────────┐│
│  │ 标题栏      [data-component="title..] ││  ← 每条：名称 + CSS选择器
│  │ 侧栏        [data-component="sideb..] ││
│  │ 底部tabs    [data-component="tabs"]   ││
│  │ ＋ 添加规则                            ││
│  │ [应用并刷新]                           ││
│  └──────────────────────────────────────┘│
│                                          │
│  深色模式:  [跟随系统 ▼]                    │
└──────────────────────────────────────────┘
```

---

## 4. DOM 裁剪

### 4.1 数据模型

```kotlin
@Serializable
data class DomPruneRule(
    val id: String = UUID.randomUUID().toString(),
    val label: String,      // 用户友好名称，如 "底部tabs"
    val selector: String    // CSS 选择器，如 "[data-component=\"tabs\"]"
)

// 预设规则（opencode-web 的 data-component 属性清单）
val presetDomPruneRules = listOf(
    DomPruneRule(label = "左侧侧栏",     selector = "[data-component=\"sidebar-nav\"]"),
    DomPruneRule(label = "桌面侧栏",     selector = "[data-component=\"sidebar-nav-desktop\"]"),
    DomPruneRule(label = "移动侧栏",     selector = "[data-component=\"sidebar-nav-mobile\"]"),
    DomPruneRule(label = "顶部标题栏",   selector = "[data-component=\"titlebar\"]"),
    DomPruneRule(label = "Session/Changes tabs", selector = "[data-component=\"tabs\"]"),
    DomPruneRule(label = "标签页列表",   selector = "[role=\"tablist\"]"),
    DomPruneRule(label = "底部导航栏",   selector = "[data-component=\"bottom-bar\"]"),
    DomPruneRule(label = "左侧轨道",     selector = "[data-component=\"sidebar-rail\"]"),
    DomPruneRule(label = "Session 分隔线", selector = "[data-slot=\"session-turn-separator\"]"),
)
```

### 4.2 WebView 注入时机

```kotlin
webView.webViewClient = object : WebViewClient() {
    override fun onPageFinished(view: WebView, url: String) {
        val rules = settingsManager.domPruneRules
        if (rules.isNotEmpty()) {
            val rulesJson = Json.encodeToString(rules.map { it.selector })
            view.evaluateJavascript("""
                (function(){
                    var selectors = $rulesJson;
                    selectors.forEach(function(sel){
                        try {
                            document.querySelectorAll(sel).forEach(function(el){ el.remove(); });
                        } catch(e) {}
                    });
                })();
            """.trimIndent(), null)
        }
    }
}
```

### 4.3 opencode-web 完整 `data-component` 清单（供参考）

**session-ui 包（聊天 UI）：**

| `data-component` | 说明 |
|---|---|
| `session-turn` | 每条对话轮次 |
| `session-turn-diffs-group` | 文件变更摘要组 |
| `session-turn-diffs-content` | 文件变更内容 |
| `text-part` | 文本消息 |
| `reasoning-part` | 推理/思考 |
| `tool-part-wrapper` | 工具调用卡片 |
| `tool-trigger` | 工具卡的行头 |
| `tool-output` | 工具输出内容 |
| `tool-action` | 工具操作按钮 |
| `tool-status-title` | 工具状态动画标题 |
| `tool-count-summary` | 工具计数摘要 |
| `tool-count-label` | 工具计数标签 |
| `tool-loaded-file` | 读文件结果 |
| `tool-error-card` | 工具错误卡 |
| `context-tool-group-trigger` | 上下文工具组行头 |
| `context-tool-group-list` | 上下文工具组展开列表 |
| `basic-tool-v2` | 工具卡片 V2 |
| `task-tool-card` | 子 agent 任务卡 |
| `task-tool-spinner` | 任务 spinner |
| `task-tool-title` | 任务 agent 名 |
| `task-tool-action` | 任务跳转按钮 |
| `shell-submessage` | Shell 子消息 |
| `bash-output` | Shell 输出 |
| `edit-tool` | 编辑工具 |
| `edit-trigger` | 编辑工具行头 |
| `edit-content` | 编辑文件内容 |
| `write-tool` | 写文件工具 |
| `write-trigger` | 写文件行头 |
| `write-content` | 写文件内容 |
| `apply-patch-tool` | 补丁工具 |
| `apply-patch-file-diff` | 补丁 diff 展示 |
| `compaction-part` | 对话压缩分隔线 |
| `user-message` | 用户消息 |
| `diagnostics` | 诊断信息 |
| `exa-tool-output` | Exa 搜索结果 |
| `todos` | Todo 列表 |
| `question-answers` | 服务器问题卡片 |
| `dock-prompt` | Dock 提示条 |
| `session-review` | 会话回顾 |
| `line-comment` | 行内注释 |
| `file` | 文件附件 |
| `markdown` | Markdown 渲染区 |
| `markdown-code` | Markdown 代码块 |
| `session-progress-indicator-v2` | 进度指示器 V2 |

**app 包（应用外壳）：**

| `data-component` | 说明 |
|---|---|
| `sidebar-nav` | 通用侧栏导航 |
| `sidebar-nav-desktop` | 桌面端侧栏 |
| `sidebar-nav-mobile` | 移动端侧栏 |
| `sidebar-rail` | 侧栏轨道 |
| `titlebar` | 顶部标题栏 |
| `tabs` | Session/Changes 标签页 |
| `tabs-drag-preview` | 标签拖拽预览 |
| `home-project-row` | 首页项目行 |
| `home-session-row` | 首页会话行 |
| `home-session-search` | 首页搜索 |
| `home-session-search-panel` | 搜索面板 |
| `home-session-search-row` | 搜索结果行 |
| `getting-started` | 新手引导 |
| `getting-started-actions` | 新手引导按钮 |
| `workspace-item` | 工作区条目 |
| `session-prompt-dock` | Session 输入 dock |
| `session-followup-dock` | 追问 dock |
| `session-revert-dock` | 回退 dock |
| `session-todo-dock` | Todo dock |
| `prompt-input` | 输入框 |
| `prompt-agent-control` | Agent 选择器 |
| `prompt-model-control` | 模型选择器 |
| `prompt-variant-control` | 变体控制器 |
| `connected-providers-section` | 已连接提供商 |
| `custom-provider-section` | 自定义提供商 |
| `settings-v2-row` | 设置行 |
| `settings-v2-list` | 设置列表 |
| `settings-models-provider` | 模型提供商设置 |
| `terminal` | 内置终端 |
| `filetree` | 文件树 |
| `file-icon` | 文件图标 |
| `session-new-design` | 新设计会话视图 |
| `desktop-icon-button` | 桌面端图标按钮 |

---

## 5. 可复用代码清单

以下代码可从现有项目 `/home/mar/personal_projects/opencode-android/app/src/main/java/cn/vectory/ocdroid/` **直接复制或微调**到新项目。

### 5.1 数据模型（无需修改）

| 文件 | 行数 | 用途 |
|------|------|------|
| `data/model/HostProfile.kt` | 95 | 服务器配置数据类 + JSON 序列化 + 导入导出 |
| `data/model/Config.kt` | 56 | `HealthResponse`、`ProvidersResponse`、`ConfigProvider` |

### 5.2 持久化存储（微调：去掉当前项目特有字段）

| 文件 | 行数 | 用途 |
|------|------|------|
| `util/SettingsManager.kt` | 270 | EncryptedSharedPreferences 封装，`getTunnelPassword`/`setTunnelPassword`、BasicAuth 密码存取 |
| `data/repository/HostProfileStore.kt` | 183 | 多 profile CRUD + JSON 序列化 + SSH 迁移逻辑 |

**调整建议**：`SettingsManager` 中去掉 session、traffic 等本方案不需要的字段（或保留不做减法也行）。

### 5.3 隧道激活逻辑（核心算法，直接复用）

| 来源 | 出处 | 用途 |
|------|------|------|
| `activateTunnel()` | `OpenCodeRepository.kt:634-674` | POST `FormBody(persist_auth=off, pw=password)` 到 serverUrl |
| `checkHealthFor()` | `OpenCodeRepository.kt:361-390` | GET `{url}/global/health`，信任所有证书 |
| `TunnelActivationState` | `MainViewModel.kt:29-33` | `Idle / Loading / Success / Error` 四态 sealed class |

**提取方式**：把 `activateTunnel` 和 `checkHealthFor` 的逻辑提取为一个纯数据类 `TunnelService`（不依赖 Hilt/ViewModel），在新项目中作为单例使用。

### 5.4 信任所有证书的 OkHttp 构建（直接复用）

来源：`OpenCodeRepository.kt` 的 `trustAllSslSocketFactory()` 和 `trustAllTrustManager`。

```kotlin
// 在 TunnelService 中
private fun OkHttpClient.Builder.trustAllCerts(): OkHttpClient.Builder {
    val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, trustAll, SecureRandom())
    }
    return sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
}
```

---

## 6. 新建代码清单

以下是原项目中没有、需要在新的 WebView 项目中从零写的部分。

### 6.1 文件结构

```
app/src/main/java/com/yage/opencode_webview/
├── MainActivity.kt              # 全屏 Activity，管理 WebView + 启动流水线
├── service/
│   └── TunnelService.kt         # health probe + tunnel activate (提取2.3/2.4)
├── config/
│   ├── SettingsManager.kt       # 从旧项目复制，精简
│   ├── HostProfileStore.kt      # 从旧项目复制
│   ├── DomPruneRule.kt          # DOM裁剪规则数据 (4.1)
│   └── PresetDomPruneRules.kt   # 预设规则清单 (4.1)
├── ui/
│   ├── WebViewScreen.kt         # 主屏幕：WebView + loading overlay + 浮动齿轮
│   ├── FloatingSettingsButton.kt # 浮动齿轮 (3.2)
│   ├── SettingsSheet.kt         # 原生设置 BottomSheet (3.3)
│   ├── ProfileEditor.kt         # 服务器CRUD表单
│   └── DomPruneEditor.kt        # DOM裁剪规则编辑
├── model/
│   ├── HostProfile.kt           # 从旧项目复制
│   └── HealthResponse.kt        # 从旧项目 Config.kt 提取
```

### 6.2 ViewModel / 状态管理

```kotlin
// 轻量级：不用 Hilt，用简单的 StateFlow + Application 级单例
data class AppState(
    val currentProfile: HostProfile? = null,
    val profiles: List<HostProfile> = emptyList(),
    val startupPhase: StartupPhase = StartupPhase.PROBING,
    val domPruneRules: List<DomPruneRule> = PresetDomPruneRules,
)

sealed class StartupPhase {
    data object Probing : StartupPhase()
    data object TunnelActivating : StartupPhase()
    data object LoadingWebView : StartupPhase()
    data class Error(val message: String) : StartupPhase()
    data object Ready : StartupPhase()
}
```

### 6.3 依赖 (build.gradle.kts)

```kotlin
dependencies {
    // 核心 Android
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // OkHttp (health probe + tunnel post)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 加密存储
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")

    // WebView
    implementation("androidx.webkit:webkit:1.12.0")
}
```

---

## 7. opcode-web 加载说明

opencode 服务端在**根路径**直接 serve web SPA。WebView 只需加载 `profile.serverUrl`：

```kotlin
webView.loadUrl(currentProfile.serverUrl)
```

不需要单独构建或打包 web 前端。服务端已自带完整的移动端响应式 UI（见 `oc-ref` 中的 `mobileTitlebarPosition`、`mobileTabs`、`mobileSidebar` 等代码）。

如果用户需要注入自定义 CSS（例如强制深色模式、调整字体大小），在 `onPageFinished` 中额外注入：

```kotlin
view.evaluateJavascript("""
    (function(){
        var style = document.createElement('style');
        style.textContent = '${cssRules.replace("'", "\\'")}';
        document.head.appendChild(style);
    })();
""".trimIndent(), null)
```

---

## 8. 与本方案无关、不需要搬运的代码

| 不需要的 | 原因 |
|----------|------|
| `ChatMessageContent.kt` (1812行) | WebView 直接渲染 web 的聊天 UI |
| `MainViewModel.kt` (1640行) | 复杂状态逻辑由 WebView 内 SPA 自行管理 |
| `OpenCodeRepository.kt` (752行) | 只取隧道/健康检查两个方法，其余 API 全由 web 前端直接调 |
| `SSEClient.kt` | SSE 由 web 前端 Navigator API 处理 |
| `Message.kt` / `Part.kt` 等 消息模型 | web 前端自行管理 |
| `MainViewModelSessionActions.kt` | 会话管理由 web 前端负责 |
| Hilt 依赖注入框架 | 新项目足够简单，不需要 DI |
| `gradle/libs.versions.toml` 的复杂版本锁定 | 新项目独立管理 |
| Retrofit | 只用 OkHttp 做少量 REST 调用 |

---

## 9. 工作量估算

| 任务 | 耗时 |
|------|------|
| 新建项目骨架 (Activity + WebView) | 0.5 天 |
| 搬运数据模型 + 持久化（HostProfile/SettingsManager） | 0.5 天 |
| 启动流水线（health probe + auto tunnel + overlay） | 1.5 天 |
| 浮动齿轮 + 设置 BottomSheet UI | 1.5 天 |
| DOM 裁剪规则编辑 + 注入 | 1 天 |
| 打磨（loading 动画、错误重试、深色模式注入） | 1 天 |
| **合计** | **~6 天** |
