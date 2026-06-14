# Android Markdown Web Preview — 产品需求文档

> 工作草案 · 2026-06-14 · OpenCode Android Client Phase 7

## Bottom Line

Android 需要对齐 iOS PR #94：Files 中 Markdown 文件默认用 Web Preview 打开，用本地 WebView shell 支持 HTML-in-Markdown、CSS 卡片、inline SVG、`<details>` 和宽表；Native Preview 与 Source 保留为回退路径。这个功能服务的是手机/平板上审阅 AI 生成的 visual Markdown 报告，不改变 Markdown 作为 source of truth 的原则。

## 1. 背景

Android 当前 Files Markdown 预览走 `multiplatform-markdown-renderer-m3`，已经支持普通 Markdown 和 data URI 图片，入口在 `app/src/main/java/com/yage/opencode_client/ui/files/FilePreviewPane.kt`。这条路径稳定，但和 iOS 旧 MarkdownUI 一样，不能稳定承载 `<style>`、HTML 卡片、inline SVG 和复杂 visual layout。

iOS PR #94 已经验证了另一条路径：本地 bundle 一个 HTML/JS/CSS shell，用 `WKWebView` 渲染 Markdown，经 sanitizer 过滤后展示。Android 需要保留同样的产品语义，但用 Android WebView 与 app assets 实现。

## 2. 用户问题

AI 现在会生成越来越多 internal-facing Markdown 报告：进度卡片、状态 chip、折叠审计层、SVG 流程图、HTML/CSS 对照块。这些报告在桌面 Markdown preview 或 iOS Web Preview 中能降低阅读成本，但在 Android 当前 Native Markdown 中会退化成源码文本或失去视觉结构。

用户在移动端打开报告时，首先要快速判断方向，而不是调试渲染器。Android 需要成为和 iOS 等价的报告阅读终端。

## 3. 产品目标

1. Files 中 `.md` / `.markdown` 提供 `Web Preview`、`Native Preview`、`Markdown Source` 三种模式。
2. 默认模式为 `Web Preview`，与 iOS 当前决策保持一致。
3. Web Preview 支持 HTML-in-Markdown 的安全子集：局部 `<style>`、`div/span` 卡片、inline SVG、`details/summary`、GFM 表格、普通 Markdown 图片。
4. workspace 相对图片继续复用现有 `MarkdownImageResolver.resolveImages(...)`，先转为 data URI 再交给 WebView。
5. Web Preview 失败、大文件或用户偏好 Native 时，可以一键回退。
6. 深浅色主题下正文、卡片、代码块、chip 都保持可读。

## 4. 非目标

1. 不把 Android client 变成通用浏览器。
2. 不在第一版支持 Chat 消息 Web Preview。
3. 不在第一版实现 Mermaid、代码高亮、LaTeX 或 Graphviz。
4. 不让 WebView 直接读取 workspace 文件系统。
5. 不从 CDN 动态加载 renderer 或 sanitizer。
6. 不要求所有 Markdown 都改写成 HTML；普通 Markdown 继续可用。

## 5. 用户场景

### 场景 A：手机审阅 visual report

用户从 Chat tool 卡片点开 `docs/report.md`。报告首屏包含状态卡、语义 chip 和折叠审计层。Android 默认 Web Preview 打开，用户能直接看出哪些路径成立、哪些阻塞。若内容过大或渲染异常，用户可切到 Native 或 Source。

### 场景 B：平板对照阅读

用户在 tablet 三栏布局中保留 Chat，同时在 Files pane 打开 Markdown 报告。Web Preview 应适配中栏宽度：图片和 SVG 不溢出，宽表横向滚动，外链点击不把 preview 变成浏览器。

### 场景 C：Android 手工验收 iOS fixtures

开发时把 iOS 的 `Markdown_Web_Preview_PRD.md` 和 `Markdown_Web_Preview_RFC.md` 复制到 Android repo 的 gitignored fixture 目录，用 Android Web Preview 打开，验证 HTML/CSS 卡片、深浅色变量和 inline SVG 是否生效。

## 6. 成功标准

1. 普通 Markdown 标题、列表、链接、代码块、表格可读。
2. `<style>` + HTML card 可显示为卡片，而不是源码文本。
3. inline SVG fixture 可显示。
4. `![x](relative/path.png)` 按 Markdown 文件目录解析并显示。
5. 深色模式下没有深底深字或浅底浅字。
6. `<script>`、`onerror`、`javascript:` URL 不执行。
7. 外部链接不在 WebView 内继续浏览。
8. 同一个文件可在 Web / Native / Source 三种模式间切换且不丢内容。

## 7. 风险

第一，WebView 增加 HTML/JS 执行面。缓解方式是本地固定 JS、DOMPurify allowlist、禁任意 navigation、禁 workspace 文件系统直读。

第二，Web Preview 与 Native Preview 对 Markdown 语法会有差异。UI 文案应明确它们是两种 preview，不承诺完全一致。

第三，data URI 对大图和长文档会增大 payload。第一版复用现有 resolver，配合 oversize gate；后续再评估 `WebViewAssetLoader` 或自定义 URL scheme。

## 8. 参考

- Android 技术方案：[Android_Markdown_Web_Preview_RFC.md](Android_Markdown_Web_Preview_RFC.md)
- iOS PRD/RFC 原文在 Android 本地测试时复制到 `docs/ios_markdown_preview_fixtures/`，该目录已加入 `.gitignore`，只作手工渲染 fixture。
- iOS 实现参考：`../opencode_ios_client/docs/Markdown_Web_Preview_PRD.md`、`../opencode_ios_client/docs/Markdown_Web_Preview_RFC.md`
