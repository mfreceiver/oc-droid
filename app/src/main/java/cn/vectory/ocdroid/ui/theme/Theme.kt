package cn.vectory.ocdroid.ui.theme

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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import cn.vectory.ocdroid.util.MarkdownFontSizes
import cn.vectory.ocdroid.util.SettingsManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The resolved dark-theme state actually applied to the composition — i.e. the
 * value [OpenCodeTheme] derives from its `darkTheme` parameter (system mode OR
 * the explicit LIGHT/DARK override resolved in MainActivity from
 * `state.themeMode`). Children should read [current] instead of calling
 * isSystemInDarkTheme() so they honor a user's explicit theme override, not
 * just the system value. Default is `false` (light) for safety in previews /
 * before [OpenCodeTheme] provides a real value.
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

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
 * Build a 4-weight system font family for a given PostScript/family name.
 *
 * 镜像 [BundledSansFamily] 的 4 档结构，使 override 路径（用户设置 fontLatin/fontCJK）
 * 也保留 weight 匹配——避免用户一旦设置字体偏好，所有 Typography slot 退化为 Normal
 * weight（单条 FontFamily 不响应 fontWeight）。实际 weight 合成由平台 Typeface.create
 * 处理：对系统内建族名（sans-serif 等）响应 weight；对任意自定义名可能回落 Normal，
 * 这是平台限制，本应用无法绕开。
 */
private fun systemFontFamily(name: String): FontFamily = FontFamily(
    Font(DeviceFontFamilyName(name), weight = FontWeight.Normal),
    Font(DeviceFontFamilyName(name), weight = FontWeight.Medium),
    Font(DeviceFontFamilyName(name), weight = FontWeight.SemiBold),
    Font(DeviceFontFamilyName(name), weight = FontWeight.Bold),
)

/**
 * Resolve a stored font-family preference to a Compose [FontFamily].
 *
 * 返回 4 档 weight 系统字体族（[systemFontFamily]），与 [BundledSansFamily] 结构对称。
 * 返回 `null` 表示"未设置，用 bundled 默认"，让调用方做优先级合并。
 *
 * 优先级见 [OpenCodeTheme]：先 markdown 键（聊天是主文本面），后 app 键。
 */
private fun resolveFontFamilyOrNull(latin: String?, cjk: String?): FontFamily? {
    val latinTrimmed = latin?.trim().orEmpty()
    if (latinTrimmed.isNotEmpty()) return systemFontFamily(latinTrimmed)
    val cjkTrimmed = cjk?.trim().orEmpty()
    if (cjkTrimmed.isNotEmpty()) return systemFontFamily(cjkTrimmed)
    return null
}

@Composable
fun OpenCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    markdownFontSizes: MarkdownFontSizes = MarkdownFontSizes(),
    /**
     * §ui-scale (M3 canonical pattern — see
     * developer.android.com/develop/ui/compose/accessibility/scalable-content):
     * two independent axes applied via a LocalDensity override wrapping the
     * entire app content. Both layer ON TOP OF the system accessibility font
     * size (LocalDensity.current.fontScale already carries the OS setting).
     *
     *  - [uiFontScale]: multiplies fontScale ONLY → text resizes, layout /
     *    padding / icon sizes stay fixed. Range 0.85–1.3, default 1.0.
     *  - [uiContentScale]: multiplies density → dp dimensions AND sp text
     *    together (true "zoom"). Range 0.85–1.3, default 1.0.
     *
     * NOT implemented as per-WindowSizeClass Typography swaps: M3 explicitly
     * prescribes a consistent type scale across all window widths; window-size-
     * class is for LAYOUT decisions (list-detail, nav rail), not type scaling.
     */
    uiFontScale: Float = 1f,
    uiContentScale: Float = 1f,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val opencodeColors = if (darkTheme) DarkOpencodeColors else LightOpencodeColors

    // v2 §20 / D5 font scaffold: read the 4 font keys from the Hilt singleton
    // [SettingsManager] and assemble a FontFamily. Defaults are empty (=
    // system font).
    //
    // R-11: previously this constructed a SECOND [SettingsManager] via
    // `SettingsManager(context)`, bypassing the Hilt @Singleton graph and
    // re-running encrypted-prefs init per Activity reconstruction. Now we
    // resolve the Hilt singleton through a Hilt @EntryPoint (this Composable
    // lives outside any @HiltViewModel scope), so the Compose tree reuses the
    // singleton. (Reactive hoisting of font state is R-17 RFC scope, NOT done
    // here — this change only eliminates the second instance.)
    val settings = rememberSettingsManager()
    val appFontFamily = remember(
        settings.fontLatin,
        settings.fontCJK,
        settings.markdownFontLatin,
        settings.markdownFontCJK,
    ) {
        // For the scaffold, prefer markdown-specific keys when set (they target
        // the markdown rendering path which is the dominant text surface in
        // the chat), then fall back to the app-wide keys. All four default to empty
        // (= bundled Noto Sans VF), so the common case uses the bundled variable font.
        resolveFontFamilyOrNull(settings.markdownFontLatin, settings.markdownFontCJK)
            ?: resolveFontFamilyOrNull(settings.fontLatin, settings.fontCJK)
            ?: BundledSansFamily
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

    // §ui-scale: derive the overridden Density from the CURRENT LocalDensity
    // (which already carries the system accessibility fontScale + the device
    // density) so both OS-level settings are respected as the base we
    // multiply on top of. Computed here (not in remember) because
    // LocalDensity.current can change across configuration changes; reading
    // it fresh each recomposition keeps the override correct after a system
    // font-size / display-size change. Both factors default to 1.0 → identity
    // (no override effect, zero overhead path skipped below).
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, uiFontScale, uiContentScale) {
        Density(
            density = baseDensity.density * uiContentScale,
            fontScale = baseDensity.fontScale * uiFontScale
        )
    }
    val needsScale = uiFontScale != 1f || uiContentScale != 1f

    CompositionLocalProvider(
        LocalMarkdownFontSizes provides markdownFontSizes,
        LocalOpencodeColors provides opencodeColors,
        LocalAppFontFamily provides appFontFamily,
        LocalIsDarkTheme provides darkTheme,
        // §ui-scale: only provide the overridden Density when a non-identity
        // factor is set. The identity path (both == 1f) skips the provider so
        // the default LocalDensity passes through unchanged — zero behavioral
        // risk for users who never touch the sliders.
        *(if (needsScale) arrayOf(LocalDensity provides scaledDensity) else emptyArray()),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            // R-25: 集中下发 app-wide Shapes（5 档圆角 token），新代码用
            // MaterialTheme.shapes.small/medium/large 等而非裸 RoundedCornerShape(dp)。
            shapes = AppShapes,
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

/**
 * R-11: Hilt entryPoint that exposes the singleton [SettingsManager] to
 * top-level Composables (which live outside any @HiltViewModel scope). Used by
 * [rememberSettingsManager] so [OpenCodeTheme] reads font preferences from the
 * same Hilt singleton the rest of the app uses, instead of constructing a
 * second [SettingsManager] that bypasses the DI graph.
 *
 * [SettingsManager] is already `@Singleton @Inject constructor`-annotated, so
 * Hilt auto-provides it; this entry point only adds a resolution path from
 * non-Hilt Compose code (no [dagger.hilt.android.HiltAndroidApp]-scoped owner
 * is available at the theme level).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SettingsManagerEntryPoint {
    fun settingsManager(): SettingsManager
}

/**
 * R-11: resolves the Hilt singleton [SettingsManager] for use in a Composable.
 * `context.applicationContext` is the Hilt-attached owner (the Application is
 * `@HiltAndroidApp`), so [EntryPointAccessors.fromApplication] can return the
 * singleton. Cached with `remember(context)` so the entry-point lookup runs
 * once per Activity.
 */
@Composable
private fun rememberSettingsManager(): SettingsManager {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            SettingsManagerEntryPoint::class.java
        ).settingsManager()
    }
}
