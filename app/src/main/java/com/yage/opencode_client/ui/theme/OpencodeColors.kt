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
    borderMuted = Color(0x14000000),
    borderBase = Color(0x1A000000),
    borderStrong = Color(0x33000000),
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
    borderMuted = Color(0x14FFFFFF),
    borderBase = Color(0x1AFFFFFF),
    borderStrong = Color(0x33FFFFFF),
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
)

/**
 * 读取当前 v2 语义色。等价于 `LocalOpencodeColors.current`，但通过
 * [MaterialTheme] 入口暴露以匹配 Compose 习惯用法。
 */
val MaterialTheme.opencode: OpencodeColors
    @Composable @ReadOnlyComposable get() = LocalOpencodeColors.current
