package com.yage.opencode_client.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * opencode v2 语义色——承载 M3 ColorScheme 表达不了的 v2 专属 token。
 *
 * 明暗各一套，随 [OpenCodeTheme] 一并切换。数值取自 v2 `theme.css`
 * （`docs/opencode-web-style-reference.md` §2.4 两张语义表）。
 *
 * 字段契约为 Lane 4（聊天 UI）的依赖入口：聊天渲染器按这些字段名引用
 * （`layer02` / `borderBase` / `accentText` / `faint` / `stateSuccessFg`
 * / `bgContrast` 等），不得随意改名。
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

    // ── R-27: per-agent / git-diff 显式色（22 个）───────────────────────────
    // 原本作为顶层 `val Color(0xFF...)` 散落在 AgentTone.kt / Color.kt，不随
    // 明暗主题变化——深色模式下部分中等饱和度色对比度不足。现纳入 OpencodeColors
    // 由明暗双套分别提供。索引/语义保持不变（agent id → tone index 映射稳定）。
    //
    // (1) 4 个已知 agent 类型固定色（AgentTone.kt.agentTones）。
    val agentTones: Map<String, Color>,
    // (2) 12 个 hash fallback 调色板（AgentTone.kt.colorPalette）。
    val colorPalette: List<Color>,
    // (3) 6 个 git-diff / 文件状态语义色（Color.kt）。
    //   - addedLine/deletedLine：diff 行背景填充（浅色=浅pastel、深色=暗调）。
    //   - modifiedFile/addedFile/deletedFile/untrackedFile：文件状态图标前景。
    val addedLine: Color,
    val deletedLine: Color,
    val modifiedFile: Color,
    val addedFile: Color,
    val deletedFile: Color,
    val untrackedFile: Color,
)

/**
 * CompositionLocal 提供 v2 专属语义色。默认值在未进入 [OpenCodeTheme] 时
 * 抛错——避免子树在缺失 provider 时静默回退到错误颜色。
 */
val LocalOpencodeColors = staticCompositionLocalOf<OpencodeColors> {
    error("OpencodeColors not provided. Wrap the tree in OpenCodeTheme.")
}

/**
 * 亮色语义色。数值来源：v2 light theme.css / reference §2.4 亮色表。
 */
internal val LightOpencodeColors = OpencodeColors(
    accentText = Color(0xFF3B5CF6),
    accentTextHover = Color(0xFF3250DF),
    codeAccent = Color(0xFF263FA9),
    faint = Color(0xFF808080),
    layer01 = Color(0xFFFAFAFA),
    layer02 = Color(0xFFF2F2F2),
    layer03 = Color(0xFFEEEEEE),
    layer04 = Color(0xFFDBDBDB),
    borderMuted = Color(0xFFE8E8E8),
    borderBase = Color(0xFFD8D8D8),
    borderStrong = Color(0xFFB0B0B0),
    borderFocus = Color(0xFF7698FD),
    overlayHover = Color(0x0A000000),
    overlayPressed = Color(0x14000000),
    overlayScrim = Color(0x66000000),
    stateSuccessBg = Color(0xFFE7F9EA),
    stateSuccessFg = Color(0xFF198B43),
    stateDangerBg = Color(0xFFFCECEB),
    stateDangerFg = Color(0xFFB82D35),
    stateInfoBg = Color(0xFFECF1FE),
    stateInfoFg = Color(0xFF2C47C8),
    bgContrast = Color(0xFF242424),

    // R-27 浅色：保留原 AgentTone/Color.kt 顶层硬编码值，迁移来源可追溯。
    // agentTones 4 个已知 agent 类型固定色（与原 AgentTone.kt 一致）。
    agentTones = mapOf(
        "ask" to Color(0xFF6CB4EE),
        "build" to Color(0xFFE8A838),
        "docs" to Color(0xFF66BB6A),
        "plan" to Color(0xFFAB7FD0),
    ),
    // colorPalette 12 个 hash fallback（与原 AgentTone.kt 顺序一致，索引稳定）。
    colorPalette = listOf(
        Color(0xFF5B9BD5), // steel blue
        Color(0xFFED7D31), // orange
        Color(0xFF70AD47), // green
        Color(0xFF9B72CF), // purple
        Color(0xFFE06666), // coral
        Color(0xFF4ECDC4), // teal
        Color(0xFFF4B942), // golden
        Color(0xFF6C8EBF), // slate blue
        Color(0xFFCC6B99), // rose
        Color(0xFF7EC8A0), // mint
        Color(0xFFD4A76A), // tan
        Color(0xFF8FBCBB), // dark cyan
    ),
    // git-diff / 文件状态语义色（与原 Color.kt 一致；addedLine/deletedLine 为
    // 行背景 pastel，modifiedFile/addedFile/deletedFile/untrackedFile 为图标前景）。
    addedLine = Color(0xFFE8F5E9),
    deletedLine = Color(0xFFFFEBEE),
    modifiedFile = Color(0xFFFFA726),
    addedFile = Color(0xFF66BB6A),
    deletedFile = Color(0xFFEF5350),
    untrackedFile = Color(0xFF90A4AE),
)

/**
 * 暗色语义色。数值来源：v2 dark theme.css / reference §2.4 暗色表。
 *
 * 注意：v2 暗色下 accent 文字/链接用 `#a2bcff`（blue-400）保证对比度，
 * 而 accent 按钮底（M3 primary）用 `#3b5cf6`。两者分离——前者在这里，
 * 后者仍在 ColorScheme.primary。
 */
internal val DarkOpencodeColors = OpencodeColors(
    accentText = Color(0xFFA2BCFF),
    accentTextHover = Color(0xFFC3D4FD),
    codeAccent = Color(0xFFA2BCFF),
    faint = Color(0xFF808080),
    layer01 = Color(0xFF242424),
    layer02 = Color(0xFF2E2E2E),
    layer03 = Color(0xFF3A3A3A),
    layer04 = Color(0xFF5C5C5C),
    borderMuted = Color(0xFF2A2A2A),
    borderBase = Color(0xFF3A3A3A),
    borderStrong = Color(0xFF606060),
    borderFocus = Color(0xFF7698FD),
    overlayHover = Color(0x0AFFFFFF),
    overlayPressed = Color(0x1AFFFFFF),
    overlayScrim = Color(0x99000000),
    stateSuccessBg = Color(0xFF14361D),
    stateSuccessFg = Color(0xFF6BD586),
    stateDangerBg = Color(0xFF461516),
    stateDangerFg = Color(0xFFF17471),
    stateInfoBg = Color(0xFF1B2852),
    stateInfoFg = Color(0xFF2C47C8),
    bgContrast = Color(0xFF5C5C5C),

    // R-27 深色：在 DarkBackground #080808 上保证 ≥ 3:1 图形对比度。原则：
    // (a) agentTones/colorPalette 的中等饱和度色整体向 HSL 亮度 +8~12% 提亮，
    //     避免在纯黑背景上发闷；已高亮的色（如 teal/golden）维持。
    // (b) addedLine/deletedLine 是行背景填充——深色下切换为暗调有色底
    //     （呼应 stateSuccessBg/stateDangerBg 风格），保持 +N/-M 行的可读性。
    // (c) modifiedFile/addedFile/deletedFile/untrackedFile 是文件状态图标前景，
    //     各自取 Material 200 色阶（更亮）保证图标在暗背景上清晰。
    agentTones = mapOf(
        "ask" to Color(0xFF7CC4F4),   // #6CB4EE +~10% L
        "build" to Color(0xFFF5BC4C), // #E8A838 +~10% L
        "docs" to Color(0xFF81C784),  // #66BB6A → green 300
        "plan" to Color(0xFFC09BE0),  // #AB7FD0 +~10% L
    ),
    colorPalette = listOf(
        Color(0xFF7AB8E8), // steel blue +L
        Color(0xFFFF9352), // orange +L
        Color(0xFF8BC34A), // green → light green 500
        Color(0xFFB891E0), // purple +L
        Color(0xFFF08080), // coral → light coral
        Color(0xFF6FE0D7), // teal +L
        Color(0xFFFFCB5E), // golden +L
        Color(0xFF8AA8D4), // slate blue +L
        Color(0xFFE088B0), // rose +L
        Color(0xFF95D8B3), // mint +L
        Color(0xFFE5BC82), // tan +L
        Color(0xFFA8D0CF), // dark cyan +L
    ),
    addedLine = Color(0xFF14361D),     // 暗绿底（pastel → dark tint）
    deletedLine = Color(0xFF3A1517),   // 暗红底
    modifiedFile = Color(0xFFFFB74D),  // orange → orange 300
    addedFile = Color(0xFF81C784),     // green → green 300
    deletedFile = Color(0xFFEF9A9A),   // red → red 200
    untrackedFile = Color(0xFFB0BEC5), // blue-gray → blue-gray 200
)

/**
 * 读取当前 v2 语义色。等价于 `LocalOpencodeColors.current`，但通过
 * [MaterialTheme] 入口暴露以匹配 Compose 习惯用法。
 */
val MaterialTheme.opencode: OpencodeColors
    @Composable @ReadOnlyComposable get() = LocalOpencodeColors.current
