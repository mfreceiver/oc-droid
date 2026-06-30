package com.yage.opencode_client.ui.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
// Material 3 color system — v2 "oc-2" skin (global retheme, R-F).
//
// Strategy (docs/v2-redesign-plan.md §2.1/§2.2): keep M3 ColorScheme as the
// color carrier (avoid touching every `MaterialTheme.colorScheme.xxx` call
// site), swap the values to v2 tokens. v2-specific semantics that M3 slots
// can't express (layer-01/02/03, faint, code-accent, border-focus, state-*,
// bg-contrast) live in [OpencodeColors], provided via [LocalOpencodeColors].
//
// Numeric values come from docs/opencode-web-style-reference.md §2.4. Brand
// identity colors ([BrandPrimary] etc.) and functional diff colors
// ([AddedLine] etc.) are intentionally kept unchanged.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Brand identity. Kept at the original electric blue for any UI that still
 * references it directly. Note: v2's accent (`#3b5cf6`) is now exposed via
 * [LightPrimary]/[DarkPrimary] and [OpencodeColors.accentText], NOT via this
 * constant — decoupled so [BrandPrimary] stays a stable legacy reference.
 */
val BrandPrimary = Color(0xFF3B82F6)
val BrandGold = Color(0xFFD9A621)

/**
 * Stop button red. Material's default error red read as too dark AND too pure at
 * once. #E5484D is lighter while sitting a notch off pure red — close to iOS's
 * system red.
 */
val StopRed = Color(0xFFE5484D)

// ========== Dark Theme (v2 oc-2 dark) ==========
// Primary family — v2 accent (`#3b5cf6`). Container slots remain M3-derived
// (v2 has no direct equivalent; kept consistent with primary hue).
val DarkPrimary = Color(0xFF3B5CF6)
val DarkOnPrimary = Color(0xFFFFFFFF)
val DarkPrimaryContainer = Color(0xFF0A47A1)
val DarkOnPrimaryContainer = Color(0xFFD3E4FF)

// Secondary family — desaturated blue-gray neutral companion (unchanged).
val DarkSecondary = Color(0xFFA5BEE0)
val DarkOnSecondary = Color(0xFF0D2E50)
val DarkSecondaryContainer = Color(0xFF264565)
val DarkOnSecondaryContainer = Color(0xFFC7DBF6)

// Tertiary family — gold (carries BrandGold's role into the M3 tonal system).
val DarkTertiary = Color(0xFFE3C34D)
val DarkOnTertiary = Color(0xFF3A2F00)
val DarkTertiaryContainer = Color(0xFF534400)
val DarkOnTertiaryContainer = Color(0xFFFFE08A)

// Neutral family — v2 dark greys. bg-deep=#080808 / bg-base=#161616 /
// layer-02=#2e2e2e / text-base=#fafafa / text-muted=#aeaeae / faint=#808080.
val DarkBackground = Color(0xFF080808)
val DarkOnBackground = Color(0xFFFAFAFA)
val DarkSurface = Color(0xFF161616)
val DarkOnSurface = Color(0xFFFAFAFA)
val DarkSurfaceVariant = Color(0xFF2E2E2E)
val DarkOnSurfaceVariant = Color(0xFFAEAEAE)

val DarkOutline = Color(0xFF808080)
val DarkOutlineVariant = Color(0x14FFFFFF)

// Error family — v2 state-fg-danger (#f17471) / state-bg-danger (#461516).
val DarkError = Color(0xFFF17471)
val DarkOnError = Color(0xFFFFFFFF)
val DarkErrorContainer = Color(0xFF461516)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

// Surface tonal steps — v2 dark grey ramp. layer-01=#242424 / layer-02=#2e2e2e
// / layer-03=#3a3a3a / layer-04=#5c5c5c. Lowest/Bright kept M3-derived (no v2
// equivalent; aligned to the same neutral axis).
val DarkSurfaceDim = Color(0xFF080808)
val DarkSurfaceBright = Color(0xFF2A2F3D)
val DarkSurfaceContainerLowest = Color(0xFF0B0F15)
val DarkSurfaceContainerLow = Color(0xFF242424)
val DarkSurfaceContainer = Color(0xFF2E2E2E)
val DarkSurfaceContainerHigh = Color(0xFF3A3A3A)
val DarkSurfaceContainerHighest = Color(0xFF5C5C5C)

// Inverse + scrim. v2 bg-contrast (send button) = #5c5c5c dark.
val DarkInverseSurface = Color(0xFF5C5C5C)
val DarkInverseOnSurface = Color(0xFFFFFFFF)
val DarkInversePrimary = Color(0xFF1B5FD3)
val DarkScrim = Color(0xFF000000)

// ========== Light Theme (v2 oc-2 light) ==========
// Primary family — v2 accent (#3b5cf6). Light text on accent stays white.
val LightPrimary = Color(0xFF3B5CF6)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFD7E3FF)
val LightOnPrimaryContainer = Color(0xFF001A41)

// Secondary family — blue-gray neutral (unchanged).
val LightSecondary = Color(0xFF4F6079)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFD3E4F8)
val LightOnSecondaryContainer = Color(0xFF0A1D32)

// Tertiary family — deep gold for light surfaces (unchanged).
val LightTertiary = Color(0xFF6E5500)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFF9DC6E)
val LightOnTertiaryContainer = Color(0xFF221A00)

// Neutral family — v2 light greys. bg-deep=#fafafa / bg-base=#ffffff /
// layer-02=#f2f2f2 / text-base=#161616 / text-muted=#5c5c5c / faint=#808080.
val LightBackground = Color(0xFFFAFAFA)
val LightOnBackground = Color(0xFF161616)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF161616)
val LightSurfaceVariant = Color(0xFFF2F2F2)
val LightOnSurfaceVariant = Color(0xFF5C5C5C)

val LightOutline = Color(0xFF808080)
val LightOutlineVariant = Color(0x14000000)

// Error family — v2 state-fg-danger (#b82d35) / state-bg-danger (#fceceb).
val LightError = Color(0xFFB82D35)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFCECEB)
val LightOnErrorContainer = Color(0xFF410002)

// Surface tonal steps — v2 light grey ramp. layer-01=#fafafa / layer-02=#f2f2f2
// / layer-03=#eeeeee / layer-04=#dbdbdb.
val LightSurfaceDim = Color(0xFFFAFAFA)
val LightSurfaceBright = Color(0xFFF7F9FC)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFFAFAFA)
val LightSurfaceContainer = Color(0xFFF2F2F2)
val LightSurfaceContainerHigh = Color(0xFFEEEEEE)
val LightSurfaceContainerHighest = Color(0xFFDBDBDB)

// Inverse + scrim. v2 bg-contrast (send button) = #242424 light.
val LightInverseSurface = Color(0xFF242424)
val LightInverseOnSurface = Color(0xFFFFFFFF)
val LightInversePrimary = Color(0xFF9DB5F5)
val LightScrim = Color(0xFF000000)

// ─────────────────────────────────────────────────────────────────────────────
// Functional colors (diff viewer, git file status). These carry concrete
// semantic meaning (added/removed/modified) and are intentionally NOT part of
// the brand accent system.
//
// R-27: 这些色已迁移到 [OpencodeColors]（明暗双套）。下方顶层 val 保留为
// **deprecated 别名**指向 [LightOpencodeColors] 对应字段——仅为兼容潜在的
// 外部引用，新代码请改用 `MaterialTheme.opencode.addedFile` 等 token。
// ─────────────────────────────────────────────────────────────────────────────
@Deprecated("R-27: 改用 MaterialTheme.opencode.addedLine（明暗双套）", ReplaceWith("LightOpencodeColors.addedLine"))
val AddedLine get() = LightOpencodeColors.addedLine
@Deprecated("R-27: 改用 MaterialTheme.opencode.deletedLine（明暗双套）", ReplaceWith("LightOpencodeColors.deletedLine"))
val DeletedLine get() = LightOpencodeColors.deletedLine
@Deprecated("R-27: 改用 MaterialTheme.opencode.modifiedFile（明暗双套）", ReplaceWith("LightOpencodeColors.modifiedFile"))
val ModifiedFile get() = LightOpencodeColors.modifiedFile
@Deprecated("R-27: 改用 MaterialTheme.opencode.addedFile（明暗双套）", ReplaceWith("LightOpencodeColors.addedFile"))
val AddedFile get() = LightOpencodeColors.addedFile
@Deprecated("R-27: 改用 MaterialTheme.opencode.deletedFile（明暗双套）", ReplaceWith("LightOpencodeColors.deletedFile"))
val DeletedFile get() = LightOpencodeColors.deletedFile
@Deprecated("R-27: 改用 MaterialTheme.opencode.untrackedFile（明暗双套）", ReplaceWith("LightOpencodeColors.untrackedFile"))
val UntrackedFile get() = LightOpencodeColors.untrackedFile
