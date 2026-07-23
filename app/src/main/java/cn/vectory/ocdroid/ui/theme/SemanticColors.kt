package cn.vectory.ocdroid.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
// SemanticColors — Phase 2 Dynamic Color 全量改造后保留的「语义固定色」。
//
// 设计理念：Dynamic Color 让 M3 ColorScheme 跟随系统壁纸动态变化，大部分 UI 色
// 归一到原生 MaterialTheme.colorScheme.*。但有一类色**不应跟随壁纸**——它们承载
// 通用语义（agent 身份区分、git diff 增删改、状态成功/信息/警告），动态化反而会
// 让用户无法辨认。这些色集中在此 object。
//
// ── 状态前景色（success/info/warning）采用 @Composable 主题感知双值─────────
// 这些色用于文本/图标，对可读性（WCAG）敏感。单一中亮度值无法同时满足浅色背景
// （需深色前景）与深色背景（需浅色前景）的对比度要求——评审 maxer/glmer 实测：
// 中亮度值在浅色 surface 上仅 2.25-2.85:1，低于 3:1 图形门槛。故恢复 R-27 的
// 明暗双值，经 LocalIsDarkTheme 切换。调用方式：SemanticColors.stateSuccessFg()
// （带括号，因是 @Composable 函数）。
//
// danger（红）不在此处——M3 原生 colorScheme.error 已承载该语义，直接用原生。
//
// ── agentPalette / diff 色：固定单值─────────────────────────────────────────
// 用于身份区分（hash 着色）与背景/状态图标，对"区分度"敏感而非"文本可读性"，
// 单一中亮度值在明暗背景上均可接受（maxer 实测 agentPalette 在 #080808 上
// 5.85-9.69:1，在浅色上作为图标 tint 亦足够区分）。
// ─────────────────────────────────────────────────────────────────────────────

object SemanticColors {
    // ── 状态前景色（@Composable 主题感知双值，保证 WCAG）─────────────────────
    /** 成功状态前景（连接成功、metadata 标记、diff +N）。 */
    @Composable @ReadOnlyComposable
    fun stateSuccessFg(): Color =
        if (LocalIsDarkTheme.current) Color(0xFF6BD586) else Color(0xFF157A3B)

    /** 信息状态前景（连接中、model-switched 标记）。 */
    @Composable @ReadOnlyComposable
    fun stateInfoFg(): Color =
        if (LocalIsDarkTheme.current) Color(0xFF9DB5FF) else Color(0xFF2C47C8)

    /** 警告状态前景（上下文占用 50-75% 阶段）。 */
    @Composable @ReadOnlyComposable
    fun stateWarningFg(): Color =
        if (LocalIsDarkTheme.current) Color(0xFFFFB74D) else Color(0xFF8A5A00)

    /** Slim 模式活跃状态前景（连接成功 + slim 模式启用）。 */
    @Composable @ReadOnlyComposable
    fun stateSlimFg(): Color =
        if (LocalIsDarkTheme.current) Color(0xFF64B5F6) else Color(0xFF1565C0)

    // ── agent / workdir 身份区分（hash → 16 色调色板，固定单值）───────────────
    val agentPalette: List<Color> = listOf(
        Color(0xFF6CB4EE), Color(0xFFE8A838), Color(0xFF66BB6A), Color(0xFFAB7FD0),
        Color(0xFF5B9BD5), Color(0xFFED7D31), Color(0xFF70AD47), Color(0xFF9B72CF),
        Color(0xFFE06666), Color(0xFF4ECDC4), Color(0xFFF4B942), Color(0xFF6C8EBF),
        Color(0xFFCC6B99), Color(0xFF7EC8A0), Color(0xFFD4A76A), Color(0xFF8FBCBB),
    )

    // ── git diff / 文件状态（通用语义，固定单值）──────────────────────────────
    val addedLine: Color = Color(0xFFE8F5E9)
    val deletedLine: Color = Color(0xFFFFEBEE)
    val modifiedFile: Color = Color(0xFFFFA726)
    val addedFile: Color = Color(0xFF66BB6A)
    val deletedFile: Color = Color(0xFFEF5350)
    val untrackedFile: Color = Color(0xFF90A4AE)
}
