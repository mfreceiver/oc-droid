package com.yage.opencode_client.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.core.view.WindowCompat
import com.yage.opencode_client.util.SettingsManager

// Full Material 3 color schemes — v2 "oc-2" skin. Dynamic color (Material You)
// is deliberately NOT used: the brand is a fixed electric blue across every
// device, matching the iOS client which also pins its own primary blue rather
// than following the system accent. v2-specific semantic colors
// ([OpencodeColors]) are layered on top via [LocalOpencodeColors].

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    surfaceDim = DarkSurfaceDim,
    surfaceBright = DarkSurfaceBright,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    inversePrimary = DarkInversePrimary,
    scrim = DarkScrim,
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    surfaceDim = LightSurfaceDim,
    surfaceBright = LightSurfaceBright,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    inversePrimary = LightInversePrimary,
    scrim = LightScrim,
)

/**
 * Resolve a stored font-family preference to a Compose [FontFamily].
 *
 * v2 字体脚手架（§20 / D5）：本期只存键、不承诺完整解析（评审注：Android
 * 对任意系统字体族名支持有限）。返回 `null` 表示"未设置，用系统默认"，
 * 让调用方做优先级合并；非空 → 作为系统字体名加载（[DeviceFontFamilyName]）。
 *
 * 优先级见 [OpenCodeTheme]：先 markdown 键（聊天是主文本面），后 app 键。
 */
private fun resolveFontFamilyOrNull(latin: String?, cjk: String?): FontFamily? {
    val latinTrimmed = latin?.trim().orEmpty()
    if (latinTrimmed.isNotEmpty()) return FontFamily(Font(DeviceFontFamilyName(latinTrimmed)))
    val cjkTrimmed = cjk?.trim().orEmpty()
    if (cjkTrimmed.isNotEmpty()) return FontFamily(Font(DeviceFontFamilyName(cjkTrimmed)))
    return null
}

@Composable
fun OpenCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    markdownFontSizes: MarkdownFontSizes = MarkdownFontSizes(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val opencodeColors = if (darkTheme) DarkOpencodeColors else LightOpencodeColors

    // v2 §20 / D5 font scaffold: read the 4 font keys from SettingsManager and
    // assemble a FontFamily. Defaults are empty (= system font). The
    // SettingsManager instance here is constructed via the Application context
    // and cached with `remember` so the (relatively expensive) encrypted-prefs
    // initialization happens once per Activity, not per recomposition. The
    // underlying SharedPreferences file is shared with the Hilt @Singleton
    // instance, so writes from elsewhere are observed on next read.
    //
    // TODO(评审 Stage C #4): this constructs a SECOND [SettingsManager] that
    // bypasses the Hilt @Singleton graph. We cannot reach the Hilt instance
    // from this top-level @Composable (it lives outside any @HiltViewModel /
    // entry-point scope), so the encrypted-prefs init runs again per Activity
    // reconstruction. A future refactor should expose [SettingsManager] via a
    // Hilt entry point (or hoist this read into MainViewModel and pass the
    // resolved FontFamily down) so the Compose tree reuses the singleton.
    val context = LocalContext.current
    val settings = remember(context) { SettingsManager(context) }
    val appFontFamily = remember(
        settings.fontLatin,
        settings.fontCJK,
        settings.markdownFontLatin,
        settings.markdownFontCJK,
    ) {
        // For the scaffold, prefer markdown-specific keys when set (they target
        // the markdown rendering path which is the dominant text surface in the
        // chat), then fall back to the app-wide keys. All four default to empty
        // (= FontFamily.Default), so the common case is unchanged.
        resolveFontFamilyOrNull(settings.markdownFontLatin, settings.markdownFontCJK)
            ?: resolveFontFamilyOrNull(settings.fontLatin, settings.fontCJK)
            ?: FontFamily.Default
    }
    val typography = remember(appFontFamily) { appTypography(appFontFamily) }

    // Adapt status bar / navigation bar icon appearance to the theme: dark theme
    // → light icons over a near-black canvas, light theme → dark icons over
    // white. enableEdgeToEdge() in MainActivity keeps the system bars transparent.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalMarkdownFontSizes provides markdownFontSizes,
        LocalOpencodeColors provides opencodeColors,
        LocalAppFontFamily provides appFontFamily,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = colorScheme.background
            ) {
                content()
            }
        }
    }
}
