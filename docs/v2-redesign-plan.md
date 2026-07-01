# opencode v2 聊天页样式改造方案

> 配套参考：[`docs/opencode-web-style-reference.md`](./opencode-web-style-reference.md)（v2 token 全量数值表）。
> 本文档是**落地实施方案**：锁定范围 → 现状基线 → 逐层改造细则（含关键代码）→ 新增功能 → 实施顺序 → 验收。

---

## 0. 已锁定决策

| 项 | 决定 |
|---|---|
| 范围 | **A + B + 代码语法高亮**：视觉重构（token/排版/气泡/卡片）+ 补 v2 有而本项目缺的功能 + 代码块语法高亮 |
| 高亮方案 | **`dev.snipme:highlights`**（纯 Kotlin Multiplatform tokenizer，非 highlight.js/prism）——产 token 流 → `AnnotatedString` `SpanStyle` 映射，无 WebView/JS 运行时 |
| 字体 | **系统字体**（不打包 Inter，`FontFamily.Default`） |
| 子 agent 面包屑 | **需要**（本期补） |
| 交付 | **一次性大改**（单 PR/单分支，原子落地） |
| 前置依赖 | LSP 编译错误由其他项目并行修复，不阻塞本方案设计 |

**补齐的三项新功能：**
1. `apply_patch` 多文件 Accordion（本项目现为按文件散落 PatchCard）。
2. 回合级 diff 分组（一个助手回合内的连续写入聚合成"本轮改动"区段）。
3. 子 agent 面包屑（v2 在子会话顶部显示"父标题 /"可点回；本项目现为单返回箭头）。

### 0.1 架构与移动端决策（D1–D7，已锁定）

> 详见 `product-requirements-assessment.md`。以下决策固化到本方案。

| 决策 | 锁定值 | 影响范围 |
|---|---|---|
| **D1 后台策略** | **Best-effort，不引入前台服务**。后台通知/数据仅在进程存活于"最近任务"期间可投递；进程被回收或划掉后失效。零通知栏噪音。 | §18 通知、§15 SSE |
| **D2 本地快照** | **不上 Room**，接受冷启动全量重拉。 | §16 缓存（仅 HTTP/图片） |
| **D3 移动端效率** | 仅做：**字号/分栏适配** + **手势返回/导航** + **最大化显示面积（不常用功能隐藏/入设置/折叠菜单）**。不做单手可达专项、不做 pull-to-refresh。 | §19 |
| **D4 会话标签条** | 复用 `openSessionIds`（≤8 MRU）+ close-X；**本期不做长按菜单**（archive/fork/delete 延后）。 | §17 |
| **D5 字体** | 本期**只建配置脚手架**（4 键：`fontCJK`/`fontLatin`/`markdownFontCJK`/`markdownFontLatin`，默认系统）；picker 与打包字体延后。 | §20 |
| **D6 变更查看** | **不在聊天页加"会话/变更"双 tab**；变更/workdir 文件浏览归 Files 页（随文件功能重构，见 `files-redesign-staging.md`）。 | §21 |
| **D7 文档归属** | 架构类需求（SSE/缓存/通知/标签页）**并入本方案**（Part II）。 | — |

**协议层硬限制（知悉）**：oc SSE 无 `Last-Event-ID` 续传，后台期间事件无法精确补差，只能"回前台整体重拉/标 unread"近似。

### 0.2 Stage A 评审决议（glm 7.5 / max 6.5 / dse 7 / gpt 7 信息参考 → 修订闭环）

> 四方评审去重后 14 项阻塞已修订。以下决议为本次修订的依据，复审时重点核：

| 决议 | 内容 | 解的阻塞 |
|---|---|---|
| **R-A 通知与 SSE 解耦** | ON_STOP **仍断 SSE**（省流）；后台通知**不依赖 SSE**——独立 30s 轻量轮询 `getPendingPermissions`/`getPendingQuestions`，命中再 `notify()`。完全符合 D1（无前台服务、best-effort）。§15 与 §18 从此自洽 | §15.2↔§18 根本矛盾 |
| **R-F 全局换皮** | §2 token 改值为**全局换皮**（v2 是全局设计系统）。§5 覆盖面扩到 `ChatInputBar`/`QuestionCardView`/`TodoListPanel` 的 `surfaceVariant`；§12 验收扩到 Sessions/Files/Settings/Question/Todo 页视觉回归 | glm B1 外溢 |
| **R-E §6 重定义 + §7 收口** | `apply_patch` 是**一个 Part 内嵌 `files: List<FileChange>`**（非多 Part）→ §6 判定改为"`writePart.files.size > 1` → MultiFilePatchAccordion"；`Message` 无 `turnId` 字段 → §7 **本期不做跨 message turn diff**，收口到 per-message（并入 §6），消除重复渲染 | max B1 + §6/§7 重复 |
| **R-G §3.1 简化** | mikepenz 官方 `multiplatform-markdown-renderer-code:0.39.0` 已传递依赖 `dev.snipme:highlights:1.1.0` 并自带预置组件 → §3.1 改为"加一行依赖 + 传两个预置组件"，**不自写 CodeHighlighter**。gating 风险：该模块用 Kotlin 2.3.0 编译（本项目 2.2.10），需 build 验证 | glm B2 / gpt 高亮 |
| R-行号 | 全文"行 XXX"引用均为**建议性**（基于早期快照，已偏移）；实施时一律**按函数名+签名定位**，勿按行号 sed | glm B5 |

> Stage A 门禁为 **glmer + maxer**；修订后交此二者复审至双 ≥9 方进入 Stage B。

---

## 1. 现状基线（改造起点）

| 层 | 文件 | 现状要点 |
|---|---|---|
| 主题色 | `ui/theme/Color.kt` | 电光蓝品牌 `#3B82F6`，全套 M3 30+ slot，6 档 surface tonal，明暗双套 |
| 主题装配 | `ui/theme/Theme.kt` | `OpenCodeTheme()` 双 ColorScheme，`LocalMarkdownFontSizes` |
| 排版 | `ui/theme/Type.kt` | 15 槽 M3 Typography，`FontFamily.Default`；`MarkdownFontSizes`(body=14/code=12/reasoning=12) |
| 消息渲染 | `ui/chat/ChatMessageContent.kt`（~1475 行） | `ChatMessageList`/`MessageRow`/`PartView` + 全部 part 渲染器 |
| 输入框 | `ui/chat/ChatInputBar.kt` | `BasicTextField`，min44/max120，圆角 20，slash 命令 |
| 顶栏 | `ui/chat/ChatTopBar.kt` | 单行；子会话时 `ArrowBack + 父标题` 返回 |
| 子 agent 数据 | `data/model/Session.kt`（`parentId`）、`Part.taskSubSessionId`、`GET session/{id}/children` | **已端到端打通** |

**结论**：数据/API/子 agent 链路/折叠能力/明暗模式**均已具备**，本方案是**纯视觉重构 + 三项功能增强**。

---

## 2. Token 层改造（Color.kt / Theme.kt）

### 2.1 策略

保留 M3 `ColorScheme` 作为颜色载体（避免全树改 `MaterialTheme.colorScheme.xxx` 调用点），**换值不换槽**。对 v2 有而 M3 槽位表达不了的语义（layer-01/02/03、faint、code-accent、border-focus、state-*、bg-contrast），新增一个 **`OpencodeColors` 扩展 holder**，经 `CompositionLocal` 提供。

### 2.2 M3 槽位 → v2 值映射

> 数值取自 `docs/opencode-web-style-reference.md` §2.4。

**亮色（替换 `Color.kt` 的 `Light*` 常量）：**

| M3 slot | v2 token | 值 |
|---|---|---|
| `LightBackground` / `LightSurfaceDim` | bg-deep | `#fafafa` |
| `LightSurface` | bg-base（聊天卡） | `#ffffff` |
| `LightSurfaceVariant` | layer-02（用户气泡） | `#f2f2f2` |
| `LightSurfaceContainerLow` | layer-01 | `#fafafa` |
| `LightSurfaceContainer` | layer-02 | `#f2f2f2` |
| `LightSurfaceContainerHigh` | layer-03 | `#eeeeee` |
| `LightSurfaceContainerHighest` | layer-04 | `#dbdbdb` |
| `LightOnBackground` / `LightOnSurface` | text-base | `#161616` |
| `LightOnSurfaceVariant` | text-muted | `#5c5c5c` |
| `LightOutline` | text-faint | `#808080` |
| `LightOutlineVariant` | border-muted | `#00000014` |
| `LightPrimary` | accent | `#3b5cf6` |
| `LightOnPrimary` | text-contrast | `#ffffff` |
| `LightError` | state-fg-danger | `#b82d35` |
| `LightErrorContainer` | state-bg-danger | `#fceceb` |
| `LightInverseSurface` | bg-contrast（发送按钮） | `#242424` |
| `LightInverseOnSurface` | text-contrast | `#ffffff` |

**暗色（替换 `Dark*` 常量）：**

| M3 slot | v2 token | 值 |
|---|---|---|
| `DarkBackground` / `DarkSurfaceDim` | bg-deep | `#080808` |
| `DarkSurface` | bg-base | `#161616` |
| `DarkSurfaceVariant` | layer-02 | `#2e2e2e` |
| `DarkSurfaceContainerLow` | layer-01 | `#242424` |
| `DarkSurfaceContainer` | layer-02 | `#2e2e2e` |
| `DarkSurfaceContainerHigh` | layer-03 | `#3a3a3a` |
| `DarkOnBackground` / `DarkOnSurface` | text-base | `#fafafa` |
| `DarkOnSurfaceVariant` | text-muted | `#aeaeae` |
| `DarkOutline` | text-faint | `#808080` |
| `DarkOutlineVariant` | border-muted | `#ffffff14` |
| `DarkPrimary` | accent（按钮底） | `#3b5cf6` |
| `DarkOnPrimary` | text-contrast | `#ffffff` |
| `DarkError` | state-fg-danger | `#f17471` |
| `DarkErrorContainer` | state-bg-danger | `#461516` |
| `DarkInverseSurface` | bg-contrast | `#5c5c5c` |
| `DarkInverseOnSurface` | text-contrast | `#ffffff` |

> 注意：v2 暗色下"accent 文字/链接"用 `#a2bcff`（blue-400）以保证对比度，而"accent 按钮底"用 `#3b5cf6`。两者都落到 `OpencodeColors` 而非 M3 primary，见下。

### 2.3 新增 `OpencodeColors`（v2 专属语义）

新建 `ui/theme/OpencodeColors.kt`：

```kotlin
package cn.vectory.ocdroid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * opencode v2 语义色——承载 M3 ColorScheme 表达不了的 v2 专属 token。
 * 明暗各一套，随 [OpenCodeTheme] 一并切换。数值取自 v2 theme.css。
 * @see docs/opencode-web-style-reference.md §2.4
 */
data class OpencodeColors(
    // accent 文字 / 链接 / 图标强调（暗色用 blue-400 保证对比度）
    val accentText: Color,
    val accentTextHover: Color,
    val codeAccent: Color,          // 行内代码 path 类型
    val faint: Color,               // placeholder / 极弱文本
    // 背景层级
    val layer01: Color,
    val layer02: Color,
    val layer03: Color,
    val layer04: Color,
    // 边框（v2 用 alpha 通道，Compose 直接给 0.5px border 用色值）
    val borderMuted: Color,
    val borderBase: Color,
    val borderStrong: Color,
    val borderFocus: Color,         // 聚焦环
    // 叠加层（hover/pressed 覆盖色）
    val overlayHover: Color,
    val overlayPressed: Color,
    val overlayScrim: Color,
    // 状态色
    val stateSuccessBg: Color,
    val stateSuccessFg: Color,
    val stateDangerBg: Color,
    val stateDangerFg: Color,
    val stateInfoBg: Color,
    val stateInfoFg: Color,
    // 发送按钮底（bg-contrast）
    val bgContrast: Color,
)

val LocalOpencodeColors = staticCompositionLocalOf {
    error("OpencodeColors not provided")
}

private val LightOpencodeColors = OpencodeColors(
    accentText = Color(0xFF3B5CF6), accentTextHover = Color(0xFF3250DF),
    codeAccent = Color(0xFF263FA9), faint = Color(0xFF808080),
    layer01 = Color(0xFFFAFAFA), layer02 = Color(0xFFF2F2F2),
    layer03 = Color(0xFFEEEEEE), layer04 = Color(0xFFDBDBDB),
    borderMuted = Color(0x14000000), borderBase = Color(0x1A000000),
    borderStrong = Color(0x33000000), borderFocus = Color(0xFF7698FD),
    overlayHover = Color(0x0A000000), overlayPressed = Color(0x14000000),
    overlayScrim = Color(0x66000000),
    stateSuccessBg = Color(0xFFE7F9EA), stateSuccessFg = Color(0xFF198B43),
    stateDangerBg = Color(0xFFFCECEB), stateDangerFg = Color(0xFFB82D35),
    stateInfoBg = Color(0xFFECF1FE), stateInfoFg = Color(0xFF2C47C8),
    bgContrast = Color(0xFF242424),
)

private val DarkOpencodeColors = OpencodeColors(
    accentText = Color(0xFFA2BCFF), accentTextHover = Color(0xFFC3D4FD),
    codeAccent = Color(0xFFA2BCFF), faint = Color(0xFF808080),
    layer01 = Color(0xFF242424), layer02 = Color(0xFF2E2E2E),
    layer03 = Color(0xFF3A3A3A), layer04 = Color(0xFF5C5C5C),
    borderMuted = Color(0x14FFFFFF), borderBase = Color(0x1AFFFFFF),
    borderStrong = Color(0x33FFFFFF), borderFocus = Color(0xFF7698FD),
    overlayHover = Color(0x0AFFFFFF), overlayPressed = Color(0x1AFFFFFF),
    overlayScrim = Color(0x99000000),
    stateSuccessBg = Color(0xFF14361D), stateSuccessFg = Color(0xFF6BD586),
    stateDangerBg = Color(0xFF461516), stateDangerFg = Color(0xFFF17471),
    stateInfoBg = Color(0xFF1B2852), stateInfoFg = Color(0xFF2C47C8),
    bgContrast = Color(0xFF5C5C5C),
)

val MaterialTheme.opencode: OpencodeColors
    @Composable @ReadOnlyComposable get() = LocalOpencodeColors.current
```

在 `Theme.kt` 的 `OpenCodeTheme()` 内，按 `darkTheme` 选 light/dark 并通过 `CompositionLocalProvider(LocalOpencodeColors provides …)` 与 `LocalMarkdownFontSizes` 一并下发。

### 2.4 elevation（阴影）

v2 的 `elevation-raised`（聊天卡/输入框卡）是多层 box-shadow。Compose 用 `Modifier.shadow(elevation, shape)` + `tonalElevation` 近似：

- 聊天卡 / 输入框卡：`shadowElevation = 2.dp`，`tonalElevation = 1.dp`，shape 圆角 10.dp。
- 弹层菜单：`shadowElevation = 8.dp`。
- 发送按钮：`shadowElevation = 1.dp` + 自绘 `border 0.5dp borderStrong`（见 §6）。

> Compose 无法逐层控制 box-shadow 颜色与 blur，故以 dp 近似；视觉上足够接近 v2。

---

## 3. 排版改造（Type.kt）

系统字体不变。**对齐 v2 字号刻度**：v2 正文 14px / 行高 150%（用户）、160%（助手 markdown）。当前 `bodyLarge=16sp` 偏大，调整为：

```kotlin
// Type.kt 调整点（仅列变更项，其余槽位保留）
bodyLarge  = TextStyle(fontFamily = FontFamily.Default, fontWeight = Normal,  fontSize = 14.sp, lineHeight = 21.sp) // 14/150% 助手正文兜底
bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = Normal,  fontSize = 14.sp, lineHeight = 21.sp) // 用户气泡正文
labelMedium= TextStyle(fontFamily = FontFamily.Default, fontWeight = Medium,  fontSize = 13.sp, lineHeight = 19.sp) // 卡片标题
labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = Medium,  fontSize = 12.sp, lineHeight = 16.sp) // 时间戳/@name
titleSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = Medium,  fontSize = 14.sp, lineHeight = 21.sp)
```

`MarkdownFontSizes` 对齐 v2 markdown.css：

```kotlin
@Serializable
data class MarkdownFontSizes(
    val h1: Float = 17f, val h2: Float = 15f, val h3: Float = 13f,
    val h4: Float = 13f, val h5: Float = 13f, val h6: Float = 13f,
    val body: Float = 14f,        // 助手 markdown 正文（v2 14px）
    val code: Float = 13f,        // 代码块（v2 13px）
    val inlineCode: Float = 14f,  // 行内代码
    val quote: Float = 14f,
    val reasoning: Float = 13f,   // 思考块（v2 13px/130%）
)
```

`markdownTypography()` 调整：标题权重按 v2（h1/h2 = SemiBold 600，h3 = Medium 500）；`code`/`inlineCode` 用 `FontFamily.Monospace`。

### 3.1 代码块语法高亮（mikepenz 官方 code 模块 + `dev.snipme:highlights`，本期纳入）

**关键发现（lib-1 核查）**：无需自写高亮器。mikepenz 官方已发布 `com.mikepenz:multiplatform-markdown-renderer-code:0.39.0`，它**已传递依赖 `dev.snipme:highlights:1.1.0`**，并自带两个预置组件 `highlightedCodeBlock` / `highlightedCodeFence`（`@Composable MarkdownComponent`）。该组件内部用 `produceState` + `Dispatchers.Default` 异步高亮，明暗主题感知，未知语言降级单色——**满足全部需求**。

**选型理由**：highlight.js/prism 是 JS/DOM 库，搬进 Compose 要么每块套 WebView，要么拖 3-8MB JS 运行时。`dev.snipme:highlights` 是纯 Kotlin Multiplatform tokenizer（端口自 highlights.rs），无 WebView/JS、流式友好。

**依赖**（`gradle/libs.versions.toml`）：
```toml
[libraries]
markdownRendererCode = { module = "com.mikepenz:multiplatform-markdown-renderer-code", version.ref = "markdownRenderer" }
# 不必单独声明 highlights —— code 模块已传递依赖 dev.snipme:highlights:1.1.0
```

**接入**（`ChatMessageContent.kt` 的 `Markdown(...)` 调用处 + `ResolvedMarkdownText`）：
```kotlin
import com.mikepenz.markdown.compose.components.markdownComponents   // 非 m3 包
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence

Markdown(
    content = …,
    typography = markdownTypography(fontSizes),
    components = markdownComponents(
        codeBlock = highlightedCodeBlock,
        codeFence = highlightedCodeFence,
    ),
    imageTransformer = DataUriImageTransformer,
)
```
> **Kotlin 2.3.0 gating 风险已实测解除**（Chat-UI-1 fixer 实施时在 Kotlin 2.2.10 下编译通过，无 `Metadata version is too new`）。无需降级。

**支持的 17 语言**：KOTLIN/JAVA/PYTHON/JAVASCRIPT/TYPESCRIPT/GO/RUST/SWIFT/C/CPP/CSHARP/PHP/RUBY/PERL/DART/COFFEESCRIPT/SHELL + DEFAULT。**注意：SQL/YAML/JSON/HTML/XML/MARKDOWN 不支持**（降级单色 mono）。

**代码块外壳视觉**：mikepenz 组件自带渲染；如需对齐 v2（圆角 6 + borderBase + mono 13sp），可改为传 lambda 调 `MarkdownHighlightedCodeBlock` 并外包一层 `Surface`。

**⚠️ 唯一 gating 风险（Kotlin 版本）**：code 模块 POM 声明 `kotlin-stdlib:2.3.0`，本项目编译器为 **2.2.10**。引入后 Gradle 会把整图 stdlib 上抬到 2.3.0，可能出现 `Metadata version is too new`。**实施第一步**：在分支加依赖跑 `./gradlew :app:assembleDebug`。失败则按序（评审 N16，遵循 AGENTS.md "工具链锁定，勿擅自升级"）：
1. **钉 markdown-renderer 全栈到 ≤0.35.0**（Kotlin 2.2.x 编译，仅降 lib 不动工具链，**首选**）；
2. 升 Kotlin 到 2.3.x（**备选**，会触发上游同步冲突）；
3. force-resolve stdlib 到 2.2.10（脆弱，不推荐）。

**降级**：若上述风险无法解决，回退到 §13 偏差第 1 条（单色 mono，无高亮），不阻塞其他模块。

**代价**：APK +几百 KB（库本体）；无自写代码；一行依赖 + 两行 components。

---

## 4. 消息列表与气泡（ChatMessageContent.kt）

### 4.1 列表与行间距

v2：回合间 `gap: 24px`；消息行水平 padding 移动 16 / ≥768 20。当前 `MessageRow` 是 `padding(horizontal=16.dp, vertical=4.dp)`，回合间距靠 4dp 垂直 padding（偏小）。

调整：
- `ChatMessageList` 的 `LazyColumn` `contentPadding`/`verticalArrangement`：保持 16dp 水平；行间用 `Arrangement.spacedBy(8.dp)`（回合内部），回合之间额外加 16dp（合计 ~24dp）。
- 由于本项目未做虚拟化（小规模消息可直接 LazyColumn），不引入 `@tanstack/virtual` 对应物。

### 4.2 用户气泡（`TextPart` isUser 分支）—— before→after

**现状**：`TextPart` 的 isUser 分支为 `Surface(primary.copy(alpha=0.10f), rounded12)` + 3dp 蓝色左条 + `fillMaxWidth(0.8f)`。

**v2 目标：** 右对齐；layer-02 背景；圆角 10dp；padding `8×12`；**无左条、无蓝底**；正文 14sp/150%；最大宽 ≈82% 列宽。

**宽度实现（评审修正）**：Compose 无"`0.82.dp` 倍率"。用外层 `fillMaxWidth(0.82f).wrapContentWidth(Alignment.End)`，或 `BoxWithConstraints { maxWidth -> widthIn(max = maxWidth * 0.82f) }`。后者更精确（短文本不强制 82%）。推荐 `BoxWithConstraints` 方案。

```kotlin
@Composable
private fun TextPart(text, isUser, modifier, repository, workspaceDirectory) {
    if (isUser) {
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            val maxBubble = maxWidth * 0.82f
            Surface(
                color = LocalOpencodeColors.current.layer02,        // #f2f2f2 / #2e2e2e
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .widthIn(max = maxBubble)
            ) {
                SelectionContainer {
                    Text(
                        text = text,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    } else {
        // 助手：v2 全宽无气泡，markdown 行高 160%
        // …沿用现有 ResolvedMarkdownText / Markdown 渲染（bodyLarge 已调 14sp）
    }
}
```

### 4.3 助手文本

保持 `ResolvedMarkdownText`（图片解析）+ `Markdown`（mikepenz）。仅：行高随 §3 排版调整；`LocalContentColor` 用 `onSurface`（= text-base）。

### 4.4 回合时间戳页脚（行 329–336）

现状：`labelSmall` / `onSurfaceVariant@60%` / `padding(start=4,top=2)`。v2 的悬停元信息行高 24dp。本期保留常显（移动端无 hover），仅对齐：`labelSmall` + `faint` 色 + `padding(top=4.dp)`，用户消息右对齐、助手左对齐。

---

## 5. Part 渲染器逐个改造

> 通用规则：卡片圆角统一 **6dp**（工具/file/patch，对应 v2 `--radius-md`），气泡/聊天卡/输入框 **10dp**（`--radius-xl`）。边框用 `OpencodeColors.borderBase`（v2 0.5px，Compose 给 `1.dp` 近似）。

### 5.1 `ReasoningCard`（行 764–843）

v2：muted 色、13px/130%、无折叠按钮（常显或简单展开）。本项目保留折叠（移动端更实用）。改造：
- 头部 Surface 透明（`Color.Transparent`），图标+标题用 `onSurfaceVariant`（= muted）。
- 正文面板背景改 `layer01`（替代 `surfaceVariant@50%`），圆角 6dp。
- 正文字号用 `MarkdownFontSizes.reasoning=13f`（已调）。
- 展开/折叠图标保留。

### 5.2 `ToolCard`（行 1162+）

v2：`BasicTool` 可折叠，圆角 6，0.5px border-base，无填充背景（透明，仅 hover 浮起）。本项目现为 `surfaceVariant` 实心底。改造：
- `Surface(color = Color.Transparent, border = BorderStroke(1.dp, borderBase), shape = RoundedCornerShape(6.dp))`。
- 图标按工具类型（read/edit/bash/generic）保留分类逻辑。
- 状态：running=spinner，completed=`stateSuccessFg` 勾，error=`stateDangerFg` 叹。
- 展开体：input/output 用 `FontFamily.Monospace` 13sp / 行高 150%（= v2 bash 输出块）。
- `todowrite` 仍走内联 `TodoListInline`（v2 是 `HIDDEN_TOOLS`，本项目保留内联更实用）。

### 5.3 `FileCard`（行 462+，读工具 2 列网格）

v2：读工具可聚合成 `ContextToolGroup` 摘要（"读取了 N 个文件"折叠）。本项目已是 2 列网格。改造（视觉对齐）：
- 圆角 6，背景 `layer01`，0.5px `borderBase`。
- 文档/文件夹图标色 `accentText`（暗色 blue-400），basename 用 mono。
- 目录读仍打开 `ModalBottomSheet`（保留）。

### 5.4 `PatchCard`（行 1348+，单文件 write/edit）

v2：`ToolFileAccordion` 单文件折叠，圆角 6，0.5px border-base，diff stats。改造：
- 背景 `Color.Transparent` + `borderBase`，圆角 6。
- `+N` 用 `stateSuccessFg`，`-M` 用 `stateDangerFg`（替代 `primary`/`error`，对齐 v2 状态语义）。
- basename mono。

### 5.5 `SubAgentCard`

v2：`task` 工具卡 `hideDetails=true`——**不可展开**的纯可点击行，圆角 6，0.5px border。本项目现为 `primary@6%` 底 + `primary@25%` 边。改造对齐 v2 中性外观：
- `Surface(transparent, border = borderBase, shape = RoundedCornerShape(6.dp))`。
- 图标 `CallSplit`/`CheckCircle` 用 `accentText`。
- **`@agentName` badge 用 `accentText`**（评审修正：去蓝底后需保留强调色让用户快速识别"这是一个子任务"，靠 `@agentName` 的 accentText 弥补识别性；其余文本用 `onSurfaceVariant`）。
- 状态：running=spinner，done=`stateSuccessFg`，error=`stateDangerFg`。
- 点击 → `onOpenSubAgent(sessionId)`（逻辑不变）。

### 5.6 `ImageFilePart` / `FileAttachmentPart`（行 376/405）

- 图片圆角改 4dp（v2 markdown 图片圆角 4）。
- 文件 chip 圆角 6，背景 `layer01`，图标 `accentText`。

### 5.7 `ToolCallsRow`（合并工具行，行 623+）

v2 没有完全对应物（v2 各工具独立 BasicTool）。本项目保留"N tool calls"折叠行（移动端省空间），视觉对齐：圆角 6 + `borderBase` + 透明底。

### 5.8 全局换皮 surfaceVariant 审计清单（R-F / 评审 N14）

§2 是全局换皮，`surfaceVariant` 从蓝灰 → layer-02 中性灰会影响所有页面。除 §5.1–5.7 的 chat 内渲染器外，以下非 part 渲染器用法需对齐（实施第一步先扫描确认）：

| 位置 | 现状 | v2 对齐 |
|---|---|---|
| `ChatInputBar` 发送按钮底 / `CommandSuggestionsPanel` / `ImageAttachmentStrip` 缩略图底 | `surfaceVariant` | 随换皮变色即可（§9 外壳改造） |
| `FileCard`（§5.3）/`ToolCallsRow`（§5.7）/`PatchCard`（§5.4）/`ReasoningCard`（§5.1） | 各自现状 | 已纳入 §5 对应小节做 v2 对齐 |
| `SettingsScreen` 卡片底 / `QuestionCardView` / `TodoListPanel` | `surfaceVariant` | **仅随全局换皮变色，不做 transparent 对齐** |

**裁决（解 glmer R1 + §12 矛盾）**：chat 内渲染器做 v2 风格刻意对齐；chat 外（Settings/Question/Todo）**仅随全局换皮变色，记为可接受偏差**，避免过度改造非高频页面。

---

## 6. 新增功能：`apply_patch` 多文件 Accordion（R-E 重定义）

### 6.1 现状（评审修正后的正确诊断）

**关键事实（max B1）**：`apply_patch` 在 server 端是**一个 Part**，多文件信息内嵌在该 Part 的 `Part.files: List<FileChange>` 字段里——**不是多个 Part**。当前 `MessageRow` 对每个 write Part 渲染**一张** `PatchCard`（不是每文件一张），问题是 `PatchCard` 头部只显示 `files.first()` 的 basename，其他文件仅在展开体列出。**所以视觉问题是"header 摘要粒度"，不是"卡片散落"。**

### 6.2 方案（聚合粒度 = 单个 Part 的 `files` 字段）

新建 `ui/chat/MultiFilePatchAccordion.kt`，接受 `List<Part>`（兼容未来跨 Part，但默认场景是"1 Part · N files"）：

```kotlin
@Composable
internal fun MultiFilePatchAccordion(
    parts: List<Part>,                 // 通常 listOf(singleApplyPatchPart)
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val files = remember(parts) { parts.flatMap { it.files ?: emptyList() } }
    // 默认展开全部"非 delete"文件；status==null 也视为非 delete（评审修正）
    var expanded by remember(parts) {
        mutableStateOf(files.filter { it.status?.lowercase() != "delete" }.map { it.path }.toSet())
    }
    val oc = LocalOpencodeColors.current
    Surface(
        modifier = modifier.padding(vertical = 2.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, oc.borderBase),
        shape = RoundedCornerShape(6.dp)
    ) {
        Column {
            DiffStatHeader(parts = parts, expandedAll = expanded.size == files.size) { /* toggle all */ }
            files.forEach { fc -> FileDiffRow(fc, fc.path in expanded, onToggle = {…}, onFileClick) }
        }
    }
}
```

**`MessageRow` write 分支判定**（在现有 `writeParts` 上，已由 `ToolCardClassifier.isWriteFileOperation` 聚合）：
```kotlin
writeParts.forEach { writePart ->
    val files = writePart.files ?: emptyList()
    if (files.size > 1) {
        MultiFilePatchAccordion(parts = listOf(writePart), onFileClick = onFileClick, modifier = …)
    } else {
        PatchCard(part = writePart, onFileClick = onFileClick, modifier = …)  // 单文件沿用
    }
}
```
> 不引入 `callId` 跨 Part 判定（评审 N1：`writeParts` 已聚合，`callId` 多余）。

`FileDiffRow` 展开体：本期不做 diff 着色，显示文件路径 + `OpenInNew` 跳转；patch 文本以 mono 13sp 展示（可选）。

### 6.3 验收
- [ ] 单个 `apply_patch` 含 ≥2 文件 → 渲染一个 `MultiFilePatchAccordion`，每文件一行可独立展开/收起。
- [ ] header 显示合计 `+N -M`；默认展开非 delete 文件（含 status==null）。
- [ ] 单文件 write/edit 仍走原 `PatchCard`，无回归。

---

## 7. ~~回合级 diff 分组~~（R-E 收口：本期不做跨 message turn diff）

**修订（R-E）**：`Message` 模型**无 `turnId` 字段**，turn 边界（"上一个 user message → 下一个 user message 之间"）只能客户端启发式推断，误判风险高。且 §6 已在 **per-message 粒度**完成多文件聚合——§7 的跨 message turn diff 会与 §6 **重复渲染**同一批写入。

**决议**：**本期取消 §7 跨 message turn diff**。变更查看走独立入口（D6，归 Files 页，随文件功能重构）。v2 的整块 turn diff 依赖 server `GET session/{id}/diff` 端点，列入 §14 后续。

> §6 的 per-message 多文件 Accordion 已覆盖"一次 apply_patch 改了多文件"的核心场景。如未来需要跨 message 聚合，需先给 `Message` 加 `turnId` 或服务端支持。

---

## 8. 新增功能 C：子 agent 面包屑

### 8.1 v2 行为

子会话顶部：`[父标题] / [当前标题]`，父标题可点回（`navigateParent`）。本项目 `ChatTopBar`（行 165–189）现只有 `ArrowBack + 父标题`。

### 8.2 方案

把 `ChatTopBar` 的 parent 分支改为面包屑样式：

```kotlin
// ChatTopBar.kt，parentSessionId != null 分支替换
Row(verticalAlignment = Alignment.CenterVertically) {
    // 父段：可点回
    Text(
        text = state.parentSessionTitle ?: stringResource(R.string.chat_parent_session),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,   // muted
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .weight(1f, fill = false)
            .clickable { actions.onSelectSession(state.parentSessionId!!) }
    )
    Text(" / ", style = MaterialTheme.typography.labelMedium,
        color = LocalOpencodeColors.current.faint)
    // 当前段：不可点
    Text(
        text = currentSessionTitle,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,          // base
        maxLines = 1, overflow = TextOverflow.Ellipsis
    )
}
```

> v2 仅渲染单层父（不做完整谱系链）。本项目沿用——`Session.parentId` 可多层，但 UI 只回溯一层，与 v2 一致。多谱系后续可扩。

### 8.3 数据就绪性

`ChatTopBarState.parentSessionId`/`parentSessionTitle` 已存在（行 111–112），由 `openSubAgent` / `onSelectSession(parentId)` 闭环。**无需改 ViewModel**。

---

## 9. Composer 改造（ChatInputBar.kt）

v2 新设计：圆角 10、`bg-base`、`elevation-raised`、最小高 96（外壳）/52（编辑器）、13px weight440、发送按钮 28×28 圆角 6 + contrast 渐变底。

本项目：`BasicTextField` min44/max120，圆角 20。改造：

- 外壳 `Surface(color = surface, shape = RoundedCornerShape(10.dp), shadowElevation = 2.dp)`。
- 编辑器 padding `horizontal=16, top=16, bottom=8`，`minHeight=52.dp`，字号 `bodyMedium`（14，对齐；v2 是 13，但 14 在移动端更可读，**本期保留 14**，记为偏差）。
- 发送按钮：28×28，圆角 6，底色 `bgContrast`，图标色 `text-contrast`（白），`shadowElevation=1.dp`，disabled `alpha=0.5f`。流式中图标 `arrow-up → stop`。
- 附件 `+`：28×28 ghost，hover `overlayHover`。

> placeholder 文案沿用现有（"Ask anything…" 可后续本地化）。

---

## 10. 布局骨架（ChatScreen / 卡片化）

v2 会话页把聊天区包成一个**卡片**（圆角 10 + elevation-raised + 外缘 padding 8）。本项目 `ChatScreen` 现为全屏 Column。改造（轻量）：

- 在 `ChatScreen` 顶层 `Surface(color = background, shape = RoundedCornerShape(10.dp), shadowElevation = 2.dp, modifier = Modifier.padding(8.dp).fillMaxSize())` 包裹消息列表 + composer。
- TopBar 保持卡片外（贴顶）或并入卡片头——**本期保持 TopBar 在卡片外**（最小改动，移动端全宽顶栏更自然）。
- 平板三栏布局的 chat 列已用 `compactTypography`，卡片化后视觉一致。

> 移动端窄屏：卡片外缘 8dp padding 在全屏下略占空间，可考虑仅 ≥600dp（`WindowWidthSizeClass.Medium`）启用卡片化，手机全屏。**待实施时按真机效果定**。

---

## 11. 实施顺序（一次性大改，但内部分层落地便于 review）

**Part I — 聊天页视觉（§1–§10）：**

| 步 | 内容 | 文件 |
|---|---|---|
| 1 | Token 层：`Color.kt` 换值 + 新建 `OpencodeColors.kt` + `Theme.kt` 下发 | theme/ |
| 2 | 排版：`Type.kt` 字号 + `MarkdownFontSizes` 调整 | theme/ |
| 2b | 代码高亮：mikepenz `multiplatform-markdown-renderer-code` 依赖 + 预置组件接入 + Kotlin 2.3.0 build 验证 | theme/, ChatMessageContent.kt |
| 3 | 用户气泡重写（`TextPart` isUser） | ChatMessageContent.kt |
| 4 | 各 part 卡片视觉对齐（reasoning/tool/file/patch/subagent/image） | ChatMessageContent.kt |
| 5 | 新建 `MultiFilePatchAccordion.kt` + `MessageRow` write 分支接入（R-E：单 Part files.size>1） | chat/ |
| 5b | ~~回合级 diff 分组~~（R-E 取消，见 §7） | — |
| 6 | 子 agent 面包屑（`ChatTopBar` parent 分支） | ChatTopBar.kt |
| 7 | Composer 视觉对齐 | ChatInputBar.kt |
| 8 | 会话卡片化（ChatScreen 包裹） | ChatScreen.kt |
| 9 | 真机/模拟器校验明暗双主题 + 子 agent 跳转闭环 | — |

**Part II — 架构与移动端（§15–§21，详见各节）：**

| 步 | 内容 | 文件 |
|---|---|---|
| 11 | **SSE 重构（P0，§15）**：去 2s 轮询 + `message.updated` debounce + 去发送双刷新 + `ProcessLifecycleObserver`（ON_STOP 断/ON_START 连+catch-up） | SSEClient.kt, MainViewModel*.kt, OpenCodeApp.kt |
| 12 | **缓存（P0，§16）**：OkHttp `Cache` + Coil（替换 HttpImageHolder）+ data-URI decode 缓存 | OpenCodeRepository.kt, DataUriImageTransformer.kt |
| 13 | **会话标签条（§17）**：下拉菜单 → 常驻 `LazyRow` tab 条 | ChatTopBar.kt |
| 14 | **后台通知 best-effort（§18）**：权限 + Channel + 后台检测 + notify() + deep link | OpenCodeApp.kt, MainViewModelSyncActions.kt, Manifest |
| 15 | **移动端效率（§19）**：字号/分栏适配、手势返回导航、隐藏不常用功能 | MainActivity.kt, theme/, 各屏 |
| 16 | **字体脚手架（§20）**：4 键预留 + FontFamily 组装（默认系统） | SettingsManager.kt, Theme.kt |
| 17 | **变更 tab 约束（§21）**：确认聊天页无变更 tab；入口归 Files 页（随文件重构） | — |

> 因 LSP 编译错误由其他项目并行处理，步骤落地前需确认主干可编译；建议在本方案分支上基于可编译基线起步。
> **实施优先级（评审 max 修正）**：**Part II 步骤 11–12（P0 数据地基）应最先做**，优先于 Part I 视觉。它们解决流量/新鲜度根因，且不依赖视觉层。建议顺序：11 → 12 → Part I（1–10）→ 13–17。

---

## 12. 验收清单

- [ ] 亮/暗双主题下，聊天页整体观感与 v2 参考截图一致（卡片圆角 10、气泡圆角 10、工具卡圆角 6）。
- [ ] 用户消息：右对齐 layer-02 气泡，无蓝底无左条，14sp/150%。
- [ ] 助手消息：全宽无气泡，markdown 14sp/160%，标题层级正确。
- [ ] 代码块：`dev.snipme:highlights` 高亮生效（明暗各一套 v2 语法色），未知语言降级单色，流式不卡顿。
- [ ] 推理卡：muted 标题 + layer01 折叠体，13sp。
- [ ] 工具卡：透明底 + borderBase，状态色用 state-* 语义。
- [ ] 子 agent 卡：中性边框，点击跳子会话，顶部面包屑可回父。
- [ ] `apply_patch` 多文件：聚合成 Accordion，默认展开非删除文件，合计 diff stat。
- [ ] 一个 `apply_patch` 含 ≥2 文件：渲染一个 MultiFilePatchAccordion（per-message），无重复渲染。
- [ ] Composer：圆角 10 + 阴影，发送按钮 28×28 contrast 底，流式切 stop 图标。
- [ ] 子 agent 跳转闭环：父→子→面包屑回父，全程视觉一致。
- [ ] 平板三栏 + 手机单栏均不破版。

**全局换皮回归（评审 R-F / glm B1）—— §2 token 是全局换皮，必须验收其他页：**
- [ ] Sessions / Files / Settings 页在 v2 token 下视觉无破相（卡片底色、强调色一致）。
- [ ] `ChatInputBar` 内部 `surfaceVariant` 用法（3 处）、`QuestionCardView`、`TodoListPanel` 按 §5.8 审计清单处理（chat 内 v2 对齐 / chat 外随换皮变色）。

**Part II 关键边界（评审补充）：**
- [ ] §15：去轮询后流式不卡死（事件覆盖矩阵全过）；切会话/回前台流式缓冲已清。
- [ ] §16：切 host/用户/workdir 无缓存污染（no-store 生效）；Cache 单例不崩。
- [ ] §18：后台通知真能弹（独立轮询，不依赖 SSE）；未授权不后台弹权限框。
- [ ] §6：单 `apply_patch` 多文件渲染一个 Accordion（不重复）。
- [ ] §3.1：代码高亮生效（Kotlin 2.3.0 兼容性已 build 验证）。

---

## 13. 已知偏差（相对 v2，本期接受）

1. **代码块无语法高亮**（C 类，本期不做）——代码块单色 mono + 圆角 + 边框。
   → 已调整：本期纳入 `dev.snipme:highlights`，见 §3.1。
2. **行内代码着色**：v2 区分 path/url 类型；本期统一 `codeAccent`，不做类型区分。
3. **字体**：系统字体而非 Inter（决策 3）——字重/字距略有差异，可接受。
4. **Composer 字号** 14（v2 13）——移动端可读性优先。
5. **跨 message turn diff 本期不做**（R-E）：`Message` 无 `turnId`，仅做 §6 per-message 多文件 Accordion；跨 message 聚合 + git/branch scope 列入 §14 后续。
6. **多谱系面包屑**只回溯一层父（与 v2 一致）。
7. **elevation** 用 Compose shadow 近似，非 v2 多层 box-shadow 精确值。
8. **ON_START(Process) vs STARTED(Activity)**：改 `ProcessLifecycleOwner` 后，旋转屏幕不再触发 30s 节流内同步（§15.2）。用户需手动刷新或等 30s。
9. **预测式返回动画本期不开**：`enableOnBackInvokedCallback` 全局影响大，仅扩展 BackHandler 优先级（§19.2）；动画列入 §14。
10. **代码高亮语言受限**：SQL/YAML/JSON/HTML/XML/MARKDOWN 不在 `dev.snipme:highlights` 支持列表，降级单色（§3.1）。

---

## 14. 后续可选（不在本期）

- ~~C 类：代码块语法高亮~~ —— 已纳入本期（`dev.snipme:highlights`，§3.1）。
- git/branch scope 回合 diff + 跨 message turn diff（R-E 取消项；接 `GET session/{id}/diff` + `Message.turnId`）。
- 主题市场（v2 有 37 主题）——当前只移植 oc-2 默认主题。
- 消息虚拟化（消息量大时；当前 LazyColumn 足够）。
- Markdown 流式增量渲染优化（v2 的 full/live/code 投影；当前 mikepenz 每帧重渲染，量小可接受）。
- ~~Room 本地快照~~ —— D2 决定不做（接受冷启动重拉）。
- ~~前台服务可靠后台~~ —— D1 决定不做（best-effort 即可）。
- 字体 picker + 打包字体（D5，本期只建脚手架）。
- 会话标签长按菜单（archive/fork/delete，D4 延后）。

---

# Part II — 架构重构与移动端需求

> 依据 `product-requirements-assessment.md`（exp-7/8 审计）。决策见 §0.1。

## 15. SSE/同步重构（P0 · 解 #4 流量 + #5 后台最新）

**根因（exp-7）**：无 keepalive、无续传、无 catch-up；2s 轮询 + `message.updated` 全量拉 + 发送双刷新三路冗余；30s 节流盲区致快速回前台不同步；半开 socket 检测滞后；`streamingPartTexts` 切会话/catch-up 不清理 → 陈旧流式文本覆盖。

### 15.1 去冗余流量（最高杠杆）
- **删除 `startBusyPolling()` / 2s 轮询**（`MainViewModel.startBusyPolling`、`MainViewModelSyncActions.launchBusyPolling`）。`message.part.updated` 增量已覆盖流式渲染。
- **去掉发送后 1200ms 双刷新**（`MainViewModelSessionActions` 的 post-send `messageRefreshDelayMs` 二次刷新）。SSE 会推 `message.created`。
- **SSE `message.updated` 改 debounce 状态机**（评审 B3）：
  - 新增 `MutableSharedFlow<Unit> messagesRefreshTrigger`（replay=0, extraBufferCapacity=1）。
  - `launchSseCollection` 收到 `message.updated`（**仅 current session**）时 `tryEmit(Unit)`。
  - 独立协程 `messagesRefreshTrigger.debounce(500).collect { loadMessagesWithRetry(currentSessionId, resetLimit=false) }`。
  - **`selectSession` 主动 `loadMessages` 绕过 debounce**（切会话不丢首事件）。注（评审 N6）：`sendMessage` 的 `onSuccess` 已有 `onRefreshMessages`，本身不依赖 debounce；此处指 sendMessage 后到达的 SSE `message.updated` 走 debounce 路径，不与 onRefreshMessages 撞车。
- **处理 `message.part.updated` 空 delta 分支**（评审 B3）：当前代码在 `deltaEvent.messageId==null || partId==null || delta.isBlank()` 时清空 `streamingPartTexts` + 全量拉。去轮询后这是新热点。改为：**仅当 messageId/partId 非 null 但 delta 为空（part 状态翻转）→ 不清空、不拉**（靠 §15.1.4 watchdog 兜底）；真正的 `part.created`（messageId==null）才预创建空 streaming 槽。

### 15.1.4 流式心跳兜底（评审 B4，必须实现）
去轮询后这是流式**唯一兜底**，不是可选项：
- `MainViewModel` 私有 `var lastSseProgressAtMs: Long`。**更新点**：在 `handleSSEEvent`（`MainViewModelSyncActions`）内，与各 `_state.update` 同一处更新（评审建议，避免实施时找不到更新点）。
- `message.part.updated` / `message.updated` / `message.created` / `session.status` 命中 current session 时更新 `lastSseProgressAtMs = now`。
- watchdog 协程：`while(isActive){ delay(5000); if(isCurrentSessionBusy && !isLoadingMessages && now-lastSseProgressAtMs>5000){ loadMessagesWithRetry(currentSessionId,false); lastSseProgressAtMs=now } }`。
- **watchdog 生命周期（评审 N11，启动+取消都要）**：`session.status` **任意状态→busy 时启动 watchdog**；`session.status` busy→idle 时**强制** `loadMessages(currentSessionId,false)` 并**取消 watchdog**。否则第二次发消息后 watchdog 已取消不会重启 → 5s 无 delta 不触发兜底。
- 子 agent（currentSessionId 为 child）同样适用。

### 15.2 后台/前台生命周期（评审 C + B2 + B7）

**架构（评审 C 修正）**：`OpenCodeApp`（Application）**不能**持有 Activity-scoped `MainViewModel`。改用 `@Singleton AppLifecycleMonitor`：
- `OpenCodeApp.onCreate` 注册 `ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleMonitor)`。
- `AppLifecycleMonitor`（Hilt `@Singleton`）暴露 `val isInForeground: StateFlow<Boolean>`（`ON_START`→true，`ON_STOP`→false）+ 一次性 foreground event 流。
- `MainViewModel` `@Inject` 该 singleton，`init { appLifecycleMonitor.isInForeground.onEach { onForegroundChanged(it) }.launchIn(viewModelScope) }`（评审 N8 修正：`collect` 是 suspend 不能在 `init` 同步块调用，用 `.onEach{}.launchIn(viewModelScope)`）。通知模块（§18）也读此 singleton。

**生命周期行为**：
- **`ON_STOP`（进后台）**：`sseJob?.cancel()` 断 SSE（省流、避免半开 socket）；停止 §15.1.4 watchdog。**通知改由 §18 的独立轮询接管**（R-A）。
- **`ON_START`（回前台）**：catch-up——**先清流式缓冲再 reload**（评审 H/B2），且**对 currentSessionId 做 null 守卫**（评审 N13：draft 模式 / 关闭最后一个 tab 时 currentSessionId 为 null，否则 `GET /session/null/message` 4xx 误报）：
  ```kotlin
  _state.update { it.copy(streamingPartTexts = emptyMap(), streamingReasoningPart = null) }
  testConnection(force = true)   // 绕过 30s 节流
  currentSessionId?.let { loadMessages(it, resetLimit = true) }   // null 守卫
  // 其他 openSessionIds 整体标 unreadSessions（无续传，无法精确补差）
  ```
- **`selectSession` 也必须清流式缓冲**（评审 B2，当前未清 → 切会话后旧 key 拼接新 delta 致文本重复）：
  ```kotlin
  // selectSessionState 内
  _state.update { it.copy(messages = emptyList(), streamingPartTexts = emptyMap(), streamingReasoningPart = null, /* … */) }
  ```
- **ON_START(Process) vs STARTED(Activity) 差异**（评审 B7）：现有 `MainActivity.repeatOnLifecycle(STARTED){ testConnection() }` 在**旋转**时也会触发（受 30s 节流）。改用 `ProcessLifecycleOwner` 后旋转**不再**触发同步。**决议**：保留 `MainActivity` 一个 `LaunchedEffect` 仅做首次启动 `testConnection(force=false)`（命中节流无副作用）；旋转不强制同步（用户可手动刷新或等 30s）。此为已知行为差异，写入 §13。

### 15.3 SSE 鲁棒性
- 重连退避加 **jitter**：`(delay * (1 + Random.nextDouble()*0.3))`（±30%）。
- **max-attempts = 10** 后停止重连，置 `state.error = "SSE 长时间无法连接，消息可能不是最新"`，顶栏状态点提供"重连"入口。
- 可选：SSE `:ping` 心跳解析（若 oc server 发送），空闲超时主动重连。

### 15.4 验收
- [ ] 流式期间无 2s 轮询流量（抓包）。
- [ ] 后台→前台 <1s 内当前会话刷新到最新；流式缓冲已清（无陈旧文本）。
- [ ] 切会话后再切回，无幽灵流式片段/文本重复。
- [ ] 流式 5s 无增量 → watchdog 触发兜底拉取一次。
- [ ] 事件覆盖矩阵：普通文本流式 / bash 长输出 / apply_patch 多文件 / permission asked-resumed / question asked-replied / task 子 agent started-completed-error / abort —— 均不静默卡死。
- [ ] 后台期间断网/换网，回前台自动重连并 catch-up。

---

## 16. 缓存层（P0 · 解 #4 流量 + 冷启动）

**现状（exp-7）**：零缓存——无 Room、无 DataStore、无 OkHttp Cache、无图片磁盘缓存、data-URI 每次 decode。

### 16.1 OkHttp HTTP Cache（含安全约束）

**两个必须先解决的工程/安全问题（评审 D + max B5）：**

**(a) Cache 单例 vs `rebuildClients()`**：`OpenCodeRepository.configure()`（切 host）调 `rebuildClients()`→`buildOkHttpClient()` 重建 client。若每次都 `new Cache(同目录)`，OkHttp 的 `DiskLruCache` 不允许同目录多实例 → 抛 `cache is closed`。
→ **`Cache` 提为 `OpenCodeRepository` 的 `@Synchronized` 懒加载单例**（`private val httpCache by lazy { Cache(File(context.cacheDir,"okhttp"),50L*1024*1024) }`），`buildOkHttpClient()` 引用它而非 new。

**(b) 缓存污染（安全/正确性，max B5）**：OkHttp Cache key 基于 URL+method+`Vary`，**忽略请求头**（含 `Authorization` 与 `X-Opencode-Directory`）。
- **跨用户**：host A 上 user X 登录 → `GET /session` 缓存 → X 退出、Y 登录同 host → 拿到 X 的缓存（隐私泄露）。
- **跨 workdir**：`GET /file?path=README.md` 在不同 workdir 返回不同内容，URL 相同会被错缓存。
- **裁决**：**默认对所有请求加 `Cache-Control: no-store`**（拦截器内），**只对显式安全的全局 GET 开缓存**：`global/health`、`config/providers`、`agent`、`command`。这些是全局只读、与用户/workdir 无关。
- 目录作用域端点（`file`/`file/content`/`file/status`/`find/file`/`session`/`session/{id}/message` 等）**一律 no-store**。
- 实施细节：拦截器内 `if (request.url.encodedPath 属于白名单) 不加 no-store；否则加 no-store`。

**前提验证（评审 I10，gating 第一步）**：实施时先用 OkHttp `LoggingInterceptor level=HEADERS` 跑一遍，确认 `global/health`/`config/providers`/`agent`/`command` 是否返回 `ETag`/`Cache-Control`。若不返回 → 这些端点的缓存实际不命中（仅作 future-proof），不影响安全约束；不要为此加客户端 `max-stale`（会重新引入污染风险）。

### 16.2 图片缓存（评审 max B6：Coil 接口形态不匹配 → 方案 β 最小改动）

**问题（max B6）**：`DataUriImageTransformer.transform()` 是 `@Composable` 返回 `ImageData?`（mikepenz 契约）；Coil 的 `AsyncImage` 是 Composable 自身，**塞不进** `transform()` 返回值 → 直接换会编译错误。

**裁决：方案 β（保留接口形态，换底层存储，固定路线）**（评审 glm R3 + max B6）：
- 保留 `DataUriImageTransformer` + `HttpImageHolder` 的接口形态不变（`@Composable transform() → ImageData?`）——规避 Coil `AsyncImage` Composable 形态不匹配（编译错误）。
- **固定路线（评审 R3）**：HTTPS 图片预取底层用 **`OkHttpClient` 下载 + 自管 `DiskLruCache`（key→file 映射）**，`transform()` 同步查磁盘文件。Coil 仅列为"后续可选优化"（Coil 2.x 同步查磁盘非公开 API，不确定性高，本期不走）。
- 收益：HTTPS 图片磁盘缓存（不重复下载）、并行预取、无 OOM。

### 16.3 data-URI decode 缓存（评审 I7 修正）
`decodeDataUriImage()` 在**非 Composable 函数**里，不能用 `remember`。改为文件级 LRU（仿现有 `HttpImageHolder.cachedBitmaps`）：
```kotlin
private val dataUriBitmapCache = object : LruCache<String, Bitmap>(maxSize = 16 * 1024 * 1024) { override fun sizeOf(k, v) = v.byteCount }
// key = dataUriString.hashCode()（避免长 base64 作 key 的内存压力）
```
Composable 内 `val bmp = remember(part.url) { dataUriBitmapCache.get(part.url.hashCode()) ?: decodeAndPut(...) }`。

### 16.4 不做（D2）
- 不引入 Room 本地快照——接受进程死亡冷启动重拉。

### 16.5 验收
- [ ] 切换 host（`configure`）不抛 `cache is closed`；Cache 为单例。
- [ ] 切用户同 host：A 用户 `/session` 缓存不被 B 用户复用（no-store 生效）。
- [ ] 切 workdir：`/file?path=X` 不跨 workdir 串内容。
- [ ] HTTPS 图片二次进入命中磁盘缓存（不重新下载）。
- [ ] 同会话切回 base64 图片不重新 decode。

---

## 17. 会话标签条（#3，状态层 70% 就绪）

**现状（exp-8）**：`openSessionIds` MRU ≤8、`unreadSessions`、`closeSession`/`selectSession` 已接；但呈现是 `ChatTopBar` 的下拉菜单（`SessionDropdownRow`，行 417–507）。

### 17.1 改造
- 把下拉菜单替换为**常驻横向 `LazyRow` tab 条**，放 TopAppBar 下方第二行（title 槽 `widthIn(max=240dp)` 不够放横排）。
- 每个 tab：标题（截断）+ 未读点（`unreadSessions`）+ close-X（`onCloseSession`）。
- 当前会话 tab 高亮（`accentText` 下划线/底色）。
- 复用现有 `openSessions`/`onSelectSession`/`onCloseSession` 管线，**不改 ViewModel**。
- 末尾"+" affordance 跳 Sessions 页新建（替代现下拉底部的"选择或创建"）。

### 17.2 与子 agent 面包屑共存（评审 I2 修正）
- `ChatScreen` 的 `openSessions` 已**自动过滤 `parentId != null`** 的子会话（`ChatScreen.kt:81`）→ **tab 条天然不显示 sub-agent session**。
- 因此**无抢占冲突**：子 agent 会话时，tab 条仍显示其他根会话（第二行），面包屑显示在 title 槽（§8），两者位置不重叠，**共存**。
- 决议：tab 条不因进入子 agent 而隐藏。

### 17.3 验收
- [ ] 聊天页常驻可见最近 ≤8 会话 tab，点按切换 <100ms。
- [ ] 未读会话 tab 显示红点；close-X 关闭并自动选中下一个。
- [ ] 进入子 agent 会话：tab 条仍显示根会话 + title 槽显示面包屑，共存不冲突。
- [ ] 平板/横屏 tab 条不破版。

---

## 18. 后台通知 best-effort（#7，D1 + R-A）

**现状（exp-8）**：零通知基础设施。SSE payload 带 sessionId/permissionId/questionId。

**核心修正（R-A）**：原方案"通知靠 SSE 事件"与 §15.2 ON_STOP 断 SSE **矛盾**。改为**通知与 SSE 解耦**——后台独立轻量轮询 `getPendingPermissions`/`getPendingQuestions`，与 SSE 无关。这样 §15 可放心断 SSE（省流），通知仍能在后台投递。

### 18.1 实现

1. **权限（评审 J/N12 修正）**：Manifest 加 `POST_NOTIFICATIONS`。**后台不可请求运行时权限**——改为**设置页提供通知开关**（用户主动启用时请求权限，不打扰首启）。后台事件到达时若无权限 → 只记 pending/unread，回前台后 in-chat 卡片可见（不再尝试弹系统权限框）。
2. **Channel（评审 I4）**：`OpenCodeApp.onCreate`（try/catch 包住，API 26+）建两通道：
   - `"opencode.decisions"`（IMPORTANCE_HIGH）——permission + question。
   - `"opencode.errors"`（IMPORTANCE_DEFAULT）。
3. **后台检测**：复用 §15.2 的 `AppLifecycleMonitor.isInForeground: StateFlow<Boolean>`。
4. **投递机制（R-A 解耦）**：
   - **snapshot 语义**：`notificationSnapshot: MutableSet<String>` = "已通知过的 permission/question ID 集合"，**跨 ON_STOP 保留、只增不减**（评审 N4 修订：清空反而会让同 pending 项反复弹通知——用户收到→回前台→再退后台会重弹）。
   - `AppLifecycleMonitor` 在 `ON_STOP`（进后台）：**保留 snapshot 不清空**，启动**独立轮询协程**（用 `@ApplicationScope` CoroutineScope，**不**用 viewModelScope——Activity 销毁会 cancel）：每 30s 调 `repository.getPendingPermissions()` + `getPendingQuestions()`；与 snapshot diff，**新增项**（snapshot 中没有的 ID）→ `NotificationManagerCompat.notify()` 并加入 snapshot；已在 snapshot 的不重弹。
   - `ON_START`（回前台）停止该轮询（前台走 in-chat 卡片，§15.2 catch-up 接管；snapshot 继续保留）。
   - **何时收缩 snapshot**（去重的反面——已处理项需移出以便"处理后再次出现能重新通知"，但 oc 无"已处理"事件）：保守起见，snapshot **只增不减**直到进程死亡；接受"处理过的项若 server 仍返回则不再通知"的轻微 UX 损失（优于反复弹）。
   - error 通知：通知模块 collect `MainViewModel.state.error.distinctUntilChanged()`（评审建议）；变更且 `isInForeground==false` → `notify()`。error 无 snapshot 去重（错误应可重复提示）。
5. **Deep link（评审 N3 修正）**：PendingIntent → MainActivity（**需 Manifest 加 `android:launchMode="singleTop"`**，否则 `onNewIntent` 永不调用，每次通知点击堆叠新 Activity）。需处理：
   - 已有 Activity：`onNewIntent` 解析 extra → `selectSession(sessionId)`。
   - 冷启动：MainActivity `onCreate` 读 intent extra → 等 VM 初始化后 `selectSession`（session 不在本地列表时先 `loadSessions`）。

### 18.2 可靠性边界（D1）
- **不引入前台服务**。后台轮询协程随 Application 进程存活（`@ApplicationScope`，非 viewModelScope）；系统回收/划掉后失效。流量可忽略（2 端点/30s，且 §16 OkHttp Cache 对全局端点友好——但 permission/question 是 directory-scoped，走 no-store，每次实拉）。
- 与 §15.2 协调：ON_STOP 断 SSE **不影响**通知（通知靠独立轮询，不靠 SSE）。

### 18.3 验收
- [ ] 应用在后台（进程存活）时，决策/错误事件弹系统通知。
- [ ] 应用在前台时不弹通知（走 in-chat 卡片）。
- [ ] 点通知跳转到对应会话（含冷启动 + onNewIntent 两条路径）。
- [ ] 未授权通知权限时，后台事件不弹权限框，回前台提示开启。

---

## 19. 移动端效率（#2，D3 锁定三项）

### 19.1 字号/分栏适配
- **字号**：Typography 槽不硬编码 sp，改用 `LocalDensity`/系统字号缩放跟随（`fontScale`）。M3 默认已部分支持，验证无硬编码覆盖。
- **分栏**：`TabletLayout`/`LandscapeSplitLayout` 的 pane 权重（现 0.25/0.375/0.375）抽到设置项，允许用户调聊天列宽。

### 19.2 手势返回/导航
- 系统返回键/预测式返回（predictive back）在 `HorizontalPager` 页面间导航，而非只退出 Activity。
- **BackHandler 优先级（评审 I3 修正，Chat 页内）**：
  1. `parentSessionId != null`（子 agent）→ 回父会话（已实现）。
  2. `openSessionIds.size > 1` → 关闭当前 tab 并 `selectSession(下一个)`。
  3. 否则 → 默认退出 Activity（与现状一致）。
- **预测式返回兼容（评审 I9）**：`android:enableOnBackInvokedCallback="true"` 会全局影响所有 Activity，开启后旧 `onBackPressed` 不再调用，需全链路 `BackHandler` 接管。**实施前验证**：`PhoneLayout`/`ChatScreen` 的 `BackHandler` + `HorizontalPager`（foundation 1.7+ 默认支持 predictive back）兼容。若存在未接管的返回路径，**本期不开启该 manifest 属性**（保持默认 `BackHandler` 行为），仅做上述优先级扩展；预测式动画列入 §14 后续。

### 19.3 最大化显示面积（隐藏不常用功能）
- 审计各屏 TopBar 操作，低频项（如服务器管理、context 用量、todo 入口）从顶栏图标移入**折叠菜单（`OverflowMenu`）或设置**。
- 聊天页：去掉非必要装饰，让消息列表占满（配合 §10 卡片化的外缘 padding 在窄屏关闭）。
- Composer：附件/命令等次要入口收进折叠区，主区域只留输入 + 发送。

### 19.4 不做（D3）
- 不做单手可达专项布局。
- 不做 pull-to-refresh（§15 的 catch-up 已解后台最新；手动刷新走顶栏入口即可）。

### 19.5 验收
- [ ] 系统字号放大/缩小时 UI 跟随不破版。
- [ ] 返回键在子 agent/文件预览/tab 间正确导航。
- [ ] 聊天页顶栏仅留高频操作，其余入折叠菜单。

---

## 20. 字体配置脚手架（#9，D5）

### 20.1 本期只建脚手架
- `SettingsManager` 加 4 键：`fontCJK`/`fontLatin`/`markdownFontCJK`/`markdownFontLatin`，默认空（= 系统字体）。
- `OpenCodeTheme` 读这些键组装 `FontFamily`：空 → `FontFamily.Default`；有值 → 解析为系统字体族名（评审注：Android 对任意系统字体族名支持有限，本期不承诺完整解析，存键为主）。
- **Type.kt 重构（评审 I5）**：`Type.kt` 现有 **15 个 slot 全部硬编码 `fontFamily = FontFamily.Default`**，`markdownTypography()` 也硬编码。重构为：
  - 抽函数 `fun appTypography(family: FontFamily): Typography`（15 slot 用传入 `family`），`val AppTypography = appTypography(FontFamily.Default)` 作 fallback。
  - `markdownTypography(sizes, family)` 同样接收 `family` 参数。
  - `compactTypography(base)` 透传。
  - `OpenCodeTheme` 内 `CompositionLocalProvider(LocalAppFontFamily provides computed)`，从 `LocalAppFontFamily.current` 取 family 组装 Typography。
- **审计清单（实施时逐个核对）**：`Type.kt` 15 slot + `markdownTypography()` + `compactTypography()` 所有 `FontFamily.Default` 出现点，必须全部改读 `LocalAppFontFamily`。
- **不做**：选择 UI、打包字体资源、字体预览。

### 20.2 验收
- [ ] 4 键存在且默认系统字体。
- [ ] 后续 picker 可直接接这 4 键，无需改主题管线。

---

## 21. 变更查看约束（#8，D6）

- **不在聊天页引入"会话/变更"双 tab**（本项目当前也无，确保不引入）。
- 会话级 diff 与 workdir 文件浏览的独立入口归 **Files 页**，随文件功能重构（见 `files-redesign-staging.md`）一并规划。
- 本项是**约束**，无独立开发任务；仅作为 §19.3"最大化显示"的依据——聊天页不放变更入口。

---

## Part II 验收汇总

- [ ] 流式流量下降（抓包对比）：去 2s 轮询、debounce、去双刷新。
- [ ] 后台→前台 <1s 刷新到最新；非当前会话标 unread。
- [ ] 图片磁盘缓存命中；二次进入不重下载/重 decode。
- [ ] 会话标签条常驻可快速切换。
- [ ] 后台决策/错误弹系统通知（进程存活期），点按跳会话。
- [ ] 字号/分栏跟随；返回键正确导航；顶栏仅高频操作。
- [ ] 字体 4 键脚手架就位（默认系统）。
- [ ] 聊天页无变更 tab。

---

## Part II 风险与回退

- **去轮询风险**：若 `message.part.updated` 在某些 server 路径不覆盖，流式会卡。**缓解**：§15.1.4 的 5s watchdog 兜底（必须实现，非可选）；事件覆盖矩阵验收；灰度抓包验证。
- **OkHttp Cache 安全**：跨用户/workdir 污染。**缓解**：§16.1 默认 no-store，仅 4 个全局端点开缓存；切 host/用户回归测试。
- **§3.1 Kotlin 2.3.0 兼容**：code 模块 stdlib 上抬可能致编译失败。**缓解**：实施第一步 build 验证；失败则升 Kotlin 2.3.x 或钉 ≤0.35.0 或回退单色（§13）。
- **通知边界**：进程死后无通知（D1）。**缓解**：R-A 独立轮询覆盖进程存活期；文档引导说明。
- **Coil 接入**：`transform()` 返回值形态不兼容 `AsyncImage`。**缓解**：方案 β 固定路线——`HttpImageHolder` 接口形态不变，底层用 OkHttpClient + 自管 `DiskLruCache`，不经 Composable（§16.2）。
- **ON_START vs STARTED**：旋转不再触发 30s 节流内同步。**缓解**：写入 §13 已知偏差；用户可手动刷新。
- **§7 turn diff 取消**：本期无跨 message 聚合。**缓解**：§6 per-message 多文件 Accordion 已覆盖核心场景；跨 message 列入 §14 后续（需 `turnId`）。
