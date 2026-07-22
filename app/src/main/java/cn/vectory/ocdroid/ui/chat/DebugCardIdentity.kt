package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.ui.theme.BundledMonoFamily
import cn.vectory.ocdroid.ui.theme.Dimens
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

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
    Box(modifier = Modifier.fillMaxWidth()) {
        content()
        DebugBadge(name = name, source = source, part = part)
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
    val context = LocalContext.current
    return try {
        val sm = EntryPointAccessors.fromApplication(
            context.applicationContext,
            DebugCardIdentitySettingsEntryPoint::class.java,
        ).settingsManager()
        sm.debugCardIdentityEnabled
    } catch (_: Exception) { false }
}
