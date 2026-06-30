package com.yage.opencode_client.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.markdownTypography
import com.yage.opencode_client.util.MarkdownFontSizes

// R-10: MarkdownFontSizes 已下沉到 util/MarkdownFontSizes.kt（消除 util 层
// SettingsManager 对 ui.theme 的反向依赖）。UI → util 为合法依赖方向。

/** CompositionLocal to provide [MarkdownFontSizes] through the tree. */
val LocalMarkdownFontSizes = staticCompositionLocalOf { MarkdownFontSizes() }

/**
 * CompositionLocal for the app-wide [FontFamily]. Defaults to
 * [FontFamily.Default] (= system font) when no provider is present.
 *
 * v2 字体脚手架（`docs/v2-redesign-plan.md` §20 / D5）：[OpenCodeTheme] 读
 * [com.yage.opencode_client.util.SettingsManager] 4 键（fontLatin/fontCJK/
 * markdownFontLatin/markdownFontCJK），解析后经此 Local 下发。
 *
 * 所有 Typography slot 与 markdownTypography 默认从此处取 family，
 * 不再硬编码 `FontFamily.Default`（评审 I5 审计清单）。
 */
val LocalAppFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

/**
 * Build the 15-slot M3 [Typography] using the given [family]. Used by
 * [OpenCodeTheme] to inject the resolved font family (from settings) into
 * every slot. v2 字号刻度（`docs/v2-redesign-plan.md` §3）：bodyLarge=14/21、
 * bodyMedium=14/21、labelMedium=13/19、labelSmall=12/16、titleSmall=14/21。
 *
 * 审计：15 个 slot 全部用传入 [family]——无硬编码 [FontFamily.Default]。
 */
fun appTypography(family: FontFamily): Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.25).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

/**
 * Fallback [Typography] using [FontFamily.Default]. Used when no
 * [OpenCodeTheme] provider is active (e.g. previews, tests).
 */
val AppTypography: Typography = appTypography(FontFamily.Default)

/**
 * Backward-compatible alias. Existing references (`typography = Typography`)
 * continue to work; [OpenCodeTheme] builds a family-aware copy via
 * [appTypography] instead.
 */
val Typography: Typography = AppTypography

/**
 * Markdown typography with per-level font sizes from [MarkdownFontSizes].
 *
 * 字号按 v2 markdown.css（§3）：h1=17/h2=15/h3=13/h4-h6=13/body=14。
 * 标题权重按 v2：h1/h2 = SemiBold (600)，h3 = Medium (500)。
 *
 * 字体 family 默认从 [LocalAppFontFamily] 取（评审 I5 审计：不硬编码
 * [FontFamily.Default]）；`code`/`inlineCode` 固定用 [FontFamily.Monospace]。
 */
@Composable
fun markdownTypography(sizes: MarkdownFontSizes) = markdownTypography(sizes, LocalAppFontFamily.current)

/**
 * Explicit-family overload of [markdownTypography]. 便于预览/测试时绕过
 * [LocalAppFontFamily]。同样标 `@Composable`——mikepenz 的 `markdownTypography`
 * 构造器本身是 @Composable（读取 `LocalTextStyle` 等环境）。
 */
@Composable
fun markdownTypography(
    sizes: MarkdownFontSizes,
    family: FontFamily,
) = markdownTypography(
    h1 = TextStyle(fontFamily = family, fontSize = sizes.h1.sp, fontWeight = FontWeight.SemiBold),
    h2 = TextStyle(fontFamily = family, fontSize = sizes.h2.sp, fontWeight = FontWeight.SemiBold),
    h3 = TextStyle(fontFamily = family, fontSize = sizes.h3.sp, fontWeight = FontWeight.Medium),
    h4 = TextStyle(fontFamily = family, fontSize = sizes.h4.sp, fontWeight = FontWeight.Medium),
    h5 = TextStyle(fontFamily = family, fontSize = sizes.h5.sp, fontWeight = FontWeight.Medium),
    h6 = TextStyle(fontFamily = family, fontSize = sizes.h6.sp, fontWeight = FontWeight.Medium),
    text = TextStyle(fontFamily = family, fontSize = sizes.body.sp),
    paragraph = TextStyle(fontFamily = family, fontSize = sizes.body.sp),
    ordered = TextStyle(fontFamily = family, fontSize = sizes.body.sp),
    bullet = TextStyle(fontFamily = family, fontSize = sizes.body.sp),
    list = TextStyle(fontFamily = family, fontSize = sizes.body.sp),
    table = TextStyle(fontFamily = family, fontSize = sizes.body.sp),
    code = TextStyle(fontSize = sizes.code.sp, fontFamily = FontFamily.Monospace),
    inlineCode = TextStyle(fontSize = sizes.inlineCode.sp, fontFamily = FontFamily.Monospace),
    quote = TextStyle(fontFamily = family, fontSize = sizes.quote.sp),
)

/** Markdown typography with headers one size smaller than default. */
@Deprecated(
    "Use LocalMarkdownFontSizes.current + markdownTypography(sizes) instead",
    ReplaceWith("markdownTypography(LocalMarkdownFontSizes.current)")
)
@Composable
fun markdownTypographyCompact() = markdownTypography(
    h1 = MaterialTheme.typography.headlineLarge,
    h2 = MaterialTheme.typography.headlineMedium,
    h3 = MaterialTheme.typography.headlineSmall,
    h4 = MaterialTheme.typography.titleLarge,
    h5 = MaterialTheme.typography.titleMedium,
    h6 = MaterialTheme.typography.titleSmall,
    text = MaterialTheme.typography.bodyLarge,
    code = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    inlineCode = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
    quote = MaterialTheme.typography.bodyMedium,
    paragraph = MaterialTheme.typography.bodyLarge,
    ordered = MaterialTheme.typography.bodyLarge,
    bullet = MaterialTheme.typography.bodyLarge,
    list = MaterialTheme.typography.bodyLarge,
    table = MaterialTheme.typography.bodyLarge
)

/**
 * Slightly smaller typography for Files and Chat columns in tablet layout.
 *
 * 审计（评审 I5）：[compactTypography] 不直接引用 [FontFamily.Default]——它
 * 从 `base` 复制，自动继承 `base` 中已注入的 family（来自 [appTypography]）。
 */
fun compactTypography(base: Typography): Typography = base.copy(
    bodyLarge = base.bodyLarge.copy(fontSize = 12.sp, lineHeight = 18.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 11.sp, lineHeight = 16.sp),
    bodySmall = base.bodySmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
    labelLarge = base.labelLarge.copy(fontSize = 11.sp, lineHeight = 14.sp),
    labelMedium = base.labelMedium.copy(fontSize = 10.sp, lineHeight = 12.sp),
    labelSmall = base.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
    titleLarge = base.titleLarge.copy(fontSize = 16.sp, lineHeight = 22.sp),
    titleMedium = base.titleMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    titleSmall = base.titleSmall.copy(fontSize = 12.sp, lineHeight = 18.sp)
)
