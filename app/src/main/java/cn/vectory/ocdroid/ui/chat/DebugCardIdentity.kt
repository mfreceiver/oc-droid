package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import cn.vectory.ocdroid.BuildConfig
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.ui.theme.BundledMonoFamily
import cn.vectory.ocdroid.ui.theme.Dimens
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

// ── §需求7: debug identity surface contract ───────────────────────────────
// Cards that OWN their background call [debugIdentitySurfaceColor] for their
// Surface `color`. While [DebugCardIdentity] is active it provides
// [LocalDebugCardIdentityAlpha] = [DEBUG_IDENTITY_SURFACE_ALPHA] so those
// owning surfaces become semi-transparent — visually flagging debug-rendered
// cards without dimming badges/text: those keep their own opaque colors, and
// the content subtree is NEVER given a Modifier.alpha (which would wash out
// contrast). ThinkingCapsule uses 0.95f as a production fill; debug cards go
// lower (0.88f) so they read as distinctly "debug" yet keep onSurfaceVariant
// text legible in both light and dark themes.

/**
 * Alpha applied to owning-card surfaces while debug card identity is ON.
 */
private const val DEBUG_IDENTITY_SURFACE_ALPHA = 0.88f

/** Active debug-surface alpha; 1f when debug card identity is OFF (default). */
internal val LocalDebugCardIdentityAlpha = staticCompositionLocalOf<Float> { 1f }

/**
 * §debug identity surface contract: blends [base] toward transparency by the
 * active debug alpha. Returns [base] unchanged when debug identity is OFF, so
 * production rendering is byte-identical to before.
 */
@Composable
internal fun debugIdentitySurfaceColor(base: Color): Color {
    val alpha = LocalDebugCardIdentityAlpha.current
    return if (alpha >= 1f) base else base.copy(alpha = base.alpha * alpha)
}

/**
 * §debug-card-identity: debug overlay that identifies which composable renders
 * a given chat card. When the toggle is OFF, [content] is rendered directly
 * with zero wrapping (no Box, no measurement, no overhead). When ON, a compact
 * badge is overlaid in the top-start corner showing the composable name, source
 * location, and optional part metadata.
 *
 * @param name the composable identity (caller-supplied, e.g. "ToolCard").
 * @param source the source `file:line` (caller-supplied; Compose can't self-report).
 * @param part optional Part to display type/tool/id from.
 * @param content the card content to render.
 */
@Composable
internal fun DebugCardIdentity(
    name: String,
    source: String,
    part: Part? = null,
    content: @Composable () -> Unit,
) {
    val enabled = rememberDebugCardIdentityEnabled()
    if (!enabled) {
        content()
        return
    }
    // §需求7: expose the debug-surface alpha to owning-card Surfaces rendered
    // inside [content]. The badge below is NOT a consumer — it stays opaque.
    CompositionLocalProvider(LocalDebugCardIdentityAlpha provides DEBUG_IDENTITY_SURFACE_ALPHA) {
        Box(modifier = Modifier.fillMaxWidth()) {
            content()
            DebugBadge(name = name, source = source, part = part)
        }
    }
}

@Composable
private fun DebugBadge(name: String, source: String, part: Part?) {
    val partInfo = buildString {
        part?.let { p ->
            append(" type="); append(p.type)
            if (!p.tool.isNullOrBlank()) { append(" tool="); append(p.tool) }
            append(" id="); append(p.id.take(8))
        }
    }
    val label = "$name@$source$partInfo"
    Surface(
        modifier = Modifier
            .padding(start = Dimens.spacing1, top = Dimens.spacing1)
            .semantics {},
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = BundledMonoFamily),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = Dimens.spacingCompact, vertical = Dimens.spacing1),
            maxLines = 1,
        )
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugCardIdentitySettingsEntryPoint {
    fun settingsManager(): cn.vectory.ocdroid.util.SettingsManager
}

@Composable
private fun rememberDebugCardIdentityEnabled(): Boolean {
    // §b-fixup2 (层 2): release 构建硬门控——即使 settings.debugCardIdentityEnabled
    // 被置 true（升级安装残留 / 异常状态），DebugCardIdentity 在 chat 页也不启用，
    // 半透明+badge 路径彻底不可达。debug 构建通过，正常按 settings 启用（不影响调试）。
    if (!BuildConfig.DEBUG) return false
    val context = LocalContext.current
    return try {
        val sm = EntryPointAccessors.fromApplication(
            context.applicationContext,
            DebugCardIdentitySettingsEntryPoint::class.java,
        ).settingsManager()
        sm.debugCardIdentityEnabled
    } catch (_: Exception) { false }
}
