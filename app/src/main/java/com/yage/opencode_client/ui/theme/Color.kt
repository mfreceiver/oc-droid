package com.yage.opencode_client.ui.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
// Material 3 color system — electric-blue identity.
//
// The brand reads as a single vivid electric blue across both themes (matching
// the iOS client). Dynamic color (Material You) is intentionally OFF so the
// brand stays identical on every device regardless of wallpaper. Everything is
// expanded to a full M3 color scheme (primary/secondary/tertiary families,
// neutral surface tonal steps, inverse + scrim) modeled on the x-liker theme.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Brand identity. [BrandPrimary] is the electric blue (#3B82F6) used directly by
 * UI accents (selected session chips, send affordances) and as the dark-theme
 * primary. [BrandGold] stays as the "AI is working / over budget" emphasis.
 */
val BrandPrimary = Color(0xFF3B82F6)
val BrandGold = Color(0xFFD9A621)

/**
 * Stop button red. Material's default error red read as too dark AND too pure at
 * once. #E5484D is lighter while sitting a notch off pure red — close to iOS's
 * system red.
 */
val StopRed = Color(0xFFE5484D)

// ========== Dark Theme ==========
// Primary family — brand electric blue, with a deep container tint.
val DarkPrimary = BrandPrimary
val DarkOnPrimary = Color(0xFFFFFFFF)
val DarkPrimaryContainer = Color(0xFF0A47A1)
val DarkOnPrimaryContainer = Color(0xFFD3E4FF)

// Secondary family — desaturated blue-gray neutral companion.
val DarkSecondary = Color(0xFFA5BEE0)
val DarkOnSecondary = Color(0xFF0D2E50)
val DarkSecondaryContainer = Color(0xFF264565)
val DarkOnSecondaryContainer = Color(0xFFC7DBF6)

// Tertiary family — gold (carries BrandGold's role into the M3 tonal system).
val DarkTertiary = Color(0xFFE3C34D)
val DarkOnTertiary = Color(0xFF3A2F00)
val DarkTertiaryContainer = Color(0xFF534400)
val DarkOnTertiaryContainer = Color(0xFFFFE08A)

// Neutral family — near-black canvas with a faint cool tint.
val DarkBackground = Color(0xFF0E1117)
val DarkOnBackground = Color(0xFFE1E2E9)
val DarkSurface = Color(0xFF131823)
val DarkOnSurface = Color(0xFFE1E2E9)
val DarkSurfaceVariant = Color(0xFF1E2433)
val DarkOnSurfaceVariant = Color(0xFFC0C6D6)

val DarkOutline = Color(0xFF444B65)
val DarkOutlineVariant = Color(0xFF2C3245)

// Error family.
val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

// Surface tonal steps (dark: Lowest is darkest, Highest is lightest).
val DarkSurfaceDim = Color(0xFF0A0D13)
val DarkSurfaceBright = Color(0xFF2A2F3D)
val DarkSurfaceContainerLowest = Color(0xFF0B0F15)
val DarkSurfaceContainerLow = Color(0xFF12161F)
val DarkSurfaceContainer = Color(0xFF161B26)
val DarkSurfaceContainerHigh = Color(0xFF1B212C)
val DarkSurfaceContainerHighest = Color(0xFF222838)

// Inverse + scrim.
val DarkInverseSurface = Color(0xFFE1E2E9)
val DarkInverseOnSurface = Color(0xFF2E3138)
val DarkInversePrimary = Color(0xFF1B5FD3)
val DarkScrim = Color(0xFF000000)

// ========== Light Theme ==========
// Primary family — a deeper blue so text/icons stay legible on white surfaces.
val LightPrimary = Color(0xFF1565C0)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFD7E3FF)
val LightOnPrimaryContainer = Color(0xFF001A41)

// Secondary family — blue-gray neutral.
val LightSecondary = Color(0xFF4F6079)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFD3E4F8)
val LightOnSecondaryContainer = Color(0xFF0A1D32)

// Tertiary family — deep gold for light surfaces.
val LightTertiary = Color(0xFF6E5500)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFF9DC6E)
val LightOnTertiaryContainer = Color(0xFF221A00)

// Neutral family — cool off-white.
val LightBackground = Color(0xFFF7F9FC)
val LightOnBackground = Color(0xFF1A1C22)
val LightSurface = Color(0xFFF7F9FC)
val LightOnSurface = Color(0xFF1A1C22)
val LightSurfaceVariant = Color(0xFFE0E2EC)
val LightOnSurfaceVariant = Color(0xFF44474F)

val LightOutline = Color(0xFF74777F)
val LightOutlineVariant = Color(0xFFC3C6D0)

// Error family.
val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

// Surface tonal steps (light: Lowest is lightest, Highest is darkest).
val LightSurfaceDim = Color(0xFFD6DAE0)
val LightSurfaceBright = Color(0xFFF7F9FC)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF1F3F8)
val LightSurfaceContainer = Color(0xFFEBEEF3)
val LightSurfaceContainerHigh = Color(0xFFE5E8ED)
val LightSurfaceContainerHighest = Color(0xFFDFE3E8)

// Inverse + scrim.
val LightInverseSurface = Color(0xFF2E3138)
val LightInverseOnSurface = Color(0xFFEFF0F4)
val LightInversePrimary = Color(0xFF9DB5F5)
val LightScrim = Color(0xFF000000)

// ─────────────────────────────────────────────────────────────────────────────
// Functional colors (diff viewer, git file status). These carry concrete
// semantic meaning (added/removed/modified) and are intentionally NOT part of
// the brand accent system.
// ─────────────────────────────────────────────────────────────────────────────
val AddedLine = Color(0xFFE8F5E9)
val DeletedLine = Color(0xFFFFEBEE)
val ModifiedFile = Color(0xFFFFA726)
val AddedFile = Color(0xFF66BB6A)
val DeletedFile = Color(0xFFEF5350)
val UntrackedFile = Color(0xFF90A4AE)
