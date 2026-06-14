# RFC: Android Markdown Web Preview 技术方案

> 工作草案 · 2026-06-14 · 对齐 iOS PR #94

## Bottom Line

Android Phase 7 采用本地 WebView renderer，而不是继续扩展 Compose Markdown renderer。实现路径是：Files 中 Markdown 默认进入 Web Preview；Kotlin 侧复用 `MarkdownImageResolver` 把相对图片转 data URI；WebView 加载 `app/src/main/assets/web_preview/preview.html`；本地 `markdown-it` 将 Markdown 转 HTML；`DOMPurify` 过滤危险 HTML；Compose toolbar 提供 Web / Native / Source 三态回退。

## 1. 当前基线

现有 Android Markdown 预览链路已经具备三块可复用能力：

1. `FilePreviewPane.kt` 已按扩展名把 Markdown 分派到 `PreviewMarkdown`。
2. `MarkdownImageResolver.resolveImages(...)` 已能把 workspace 相对图片转 data URI，路径语义包括当前 Markdown 文件目录、`../`、absolute workspace path 和 `sessionDirectory`。
3. 测试体系已覆盖 resolver、preview kind 和 Compose component test，见 `docs/test.md`。

缺口是 Native Compose Markdown renderer 不执行 HTML/CSS，也不能稳定呈现 inline SVG 和 visual cards。这是 renderer 能力边界，不是简单样式调整。

## 2. 目标架构

```text
Markdown file content
        │
        ▼
FilePreviewPane
        │ mode = web/native/source
        ▼
MarkdownWebPreviewPane
        │ resolve relative images to data URI
        ▼
Android WebView
        │ loads app assets web_preview/preview.html
        ▼
markdown-it + DOMPurify + preview.css
        │
        ▼
sanitized HTML preview
```

## 3. 文件与职责

| 文件 | 职责 |
|---|---|
| `ui/files/FilePreviewPane.kt` | 增加 `MarkdownPreviewMode`、toolbar mode menu、oversize gate、三态分派 |
| `ui/files/MarkdownWebPreviewPane.kt` | Compose 外层容器，负责 resolver、loading/error/fallback 状态 |
| `ui/files/MarkdownWebView.kt` | `AndroidView` 包 WebView，负责加载 asset shell、注入 JSON payload、处理 bridge |
| `ui/files/MarkdownWebPreviewBridge.kt` | WebView JS bridge 的 link/error/image message 解析 |
| `app/src/main/assets/web_preview/*` | `preview.html/css/js`、`markdown-it.min.js`、`purify.min.js` |
| `app/src/test/.../MarkdownImageResolverTest.kt` | 补充 Web Preview 需要的相对路径和 data URI case |
| `app/src/androidTest/.../MarkdownWebPreviewInstrumentedTest.kt` | fixture 渲染、mode switching、安全过滤 component test |

## 4. UI 设计

Markdown 文件预览 toolbar 右侧新增 mode menu：

```text
Preview Mode
  Web Preview      默认
  Native Preview   Compose Markdown renderer
  Markdown Source  原文
```

大文件策略对齐 iOS：Web Preview 超过阈值时先显示确认状态，避免直接把超长 payload 注入 WebView。建议第一版阈值：总长度 `60_000`、单行 `5_000`，后续根据 Android WebView 性能调整。

错误状态提供两个按钮：`Open Native Preview` 和 `Open Markdown Source`。错误信息只显示摘要，不把完整 Markdown 或凭证打印到 UI/log。

## 5. WebView 安全模型

Android WebView 必须按最小权限运行：

1. Shell 只从 app assets 加载，不访问 CDN。
2. `WebSettings.allowFileAccess = false`、`allowContentAccess = false`、`domStorageEnabled = false`。
3. JavaScript 只为本地 renderer 开启。
4. Markdown payload 用 JSON serializer 生成，不手写字符串拼接。
5. `WebViewClient.shouldOverrideUrlLoading` 默认拦截 navigation。
6. `http/https` 外链交给系统浏览器或 app 外链策略。
7. `#fragment` 留在 WebView 内滚动。
8. workspace 相对链接通过 bridge 回到 Files 路由。
9. 其它 scheme 默认阻断，第一版不隐式支持 `mailto:` / `tel:`。

DOMPurify allowlist 第一版允许：基础 Markdown 标签、`details/summary`、`div/span`、`img`、table 相关标签、inline SVG 常用标签、局部 `style`。必须移除：`script`、`iframe`、`object`、`embed`、`form`、`input`、`on*`、`javascript:`、`vbscript:`。

允许 `<style>` 是产品决策：internal writing 已经依赖 CSS card 和主题变量。安全边界靠 sanitizer、禁止任意 navigation、禁止 workspace 文件直读来控制。

## 6. 图片与链接

图片解析沿用现有 Kotlin resolver：

```kotlin
MarkdownImageResolver.resolveImages(
    text = MarkdownImageResolver.normalizeStandaloneImageBlocks(content),
    markdownFilePath = resolveRelativePreviewPath(filePath, sessionDirectory),
    workspaceDirectory = sessionDirectory,
    fetchContent = { path -> repository.getFileContent(path).getOrThrow() }
)
```

这样 Web Preview、Native Preview 和 Chat Markdown 共享同一套 workspace 相对路径语义。WebView 不直接读取 repo 文件。

链接第一版只做三类：fragment anchor、`http/https` 外链、workspace 相对链接。workspace 相对链接解析可复用 `MarkdownImageResolver.normalizeImagePath(...)` 的路径语义，但建议后续抽成更通用的 `MarkdownPathResolver`，避免函数名继续绑定 image。

## 7. 测试计划

### Unit

1. `MarkdownImageResolverTest`：补充 `../`、workspace absolute、query/fragment stripping、data URI skip。
2. 新增 `MarkdownWebPreviewPayloadTest`：验证 JSON payload 不拼接原始 Markdown 字符串，危险内容仍能安全编码。
3. 若抽出 `MarkdownPreviewMode` 或 oversize helper，补纯函数测试。

### Component / Instrumented

1. `MarkdownWebPreviewInstrumentedTest` 用 `createComposeRule()` 直接渲染 `MarkdownWebPreviewPane`，不连 server。
2. fixture 覆盖 `html_cards`、`dark_theme_cards`、`inline_svg`、`wide_table`、`malicious_script`、`semantic_chips`。
3. 断言 WebView 容器存在、sentinel text 可见、mode menu 可切换到 Source / Native。
4. 安全 fixture 至少断言危险脚本副作用没有出现；如 WebView DOM 读取不稳定，使用 JS bridge 回传 sanitized sentinel。

### Integration / LLM-driven UI

第一版不要求连真实 server 触发 write。若需要端到端验收，只使用 read-only fixture 或已有文件，通过 Chat 文件卡跳转到 Files 后确认 preview mode。真实 write 类场景仍留在 component 层用假数据，避免污染 workspace。

### 命令

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

需要 WebView/Compose instrumented test 时，在 emulator 上运行：

```bash
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
./gradlew connectedDebugAndroidTest
```

## 8. 开发计划

### Phase 0：资源 shell 闭环

1. 新增 `app/src/main/assets/web_preview/preview.html`、`preview.css`、`preview.js`、`markdown-it.min.js`、`purify.min.js`。
2. 先在桌面或 WebView component test 中验证 `window.renderMarkdown(payload)`、主题变量、DOMPurify 规则。
3. fixture 使用 iOS 同款 Markdown 内容；iOS PRD/RFC 复制到 `docs/ios_markdown_preview_fixtures/` 作手工测试，但该目录不入 git。

### Phase 1：Files MVP

1. 在 `FilePreviewPane.kt` 中加入 `MarkdownPreviewMode`，默认 `Web`。
2. Toolbar 增加 mode menu，给 instrumented test 留 stable content description / test tag。
3. 新增 `MarkdownWebPreviewPane`，接入 resolver 后把 Markdown 传给 WebView。
4. Source 模式直接显示 `PreviewPlainText`；Native 模式复用现有 `PreviewMarkdown`。
5. 加 loading、error、oversize fallback。

### Phase 2：安全与链接

1. 收紧 WebView settings 与 `WebViewClient` navigation policy。
2. JS bridge 处理 link/error/image 事件。
3. 外链走系统浏览器；workspace relative link 回 Files；image click 留作 future。
4. DOMPurify allowlist 对齐 iOS，保留 Android 特有 regression fixture。

### Phase 3：测试与 polish

1. 补 unit tests。
2. 补 Compose/WebView instrumented tests。
3. 跑 `testDebugUnitTest`、`assembleDebug`，有 emulator 时跑 targeted `connectedDebugAndroidTest`。
4. 手工打开 `docs/ios_markdown_preview_fixtures/Markdown_Web_Preview_PRD.md` 与 RFC，验证卡片、深浅色、inline SVG、宽表。

## 9. 后续路线

1. 大图优化：从 data URI 迁移到 `WebViewAssetLoader` 或自定义 scheme。
2. `.html` artifact 浏览：独立安全策略，不复用 Markdown sanitizer 假设。
3. Mermaid / code highlight：作为 visual enhancement 单独评估性能和 bundle size。
