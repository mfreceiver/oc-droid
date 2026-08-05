package cn.vectory.ocdroid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.theme.AppSectionHeader
import cn.vectory.ocdroid.ui.theme.Dimens
import cn.vectory.ocdroid.util.SettingsManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * settings/debug — standalone debug page with diagnostic toggles.
 *
 * Contains the DebugLogSection (migrated from SettingsAboutRoute) plus a new
 * SSE-disabled toggle (REST-only degraded mode). The SSE toggle reads/writes
 * [SettingsManager.sseDisabled] directly via the Hilt EntryPoint pattern
 * already used by [DebugLogSection].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDebugRoute(
    @Suppress("UNUSED_PARAMETER") hostVM: cn.vectory.ocdroid.ui.HostViewModel,
    @Suppress("UNUSED_PARAMETER") settingsVM: cn.vectory.ocdroid.ui.SettingsViewModel,
    onBack: () -> Unit,
) {
    SettingsSubRouteScaffold(titleRes = R.string.settings_section_debug, onBack = onBack) { mod ->
        Column(
            modifier = mod.verticalScroll(rememberScrollState()),
        ) {
            // §sse-disabled-debug-toggle: REST-only degraded mode toggle.
            // Reads/writes SettingsManager.sseDisabled via the Hilt EntryPoint.
            val settingsManager = rememberSseDisabledSettingsManager()
            var sseDisabled by remember { mutableStateOf(settingsManager.sseDisabled) }

            AppSectionHeader(text = stringResource(R.string.settings_section_debug))
            ListItem(
                headlineContent = {
                    Text(
                        text = stringResource(R.string.settings_debug_sse_disabled_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.settings_debug_sse_disabled_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = sseDisabled,
                        onCheckedChange = { next ->
                            settingsManager.sseDisabled = next
                            sseDisabled = next
                        },
                    )
                },
                modifier = Modifier.clickable {
                    val next = !sseDisabled
                    settingsManager.sseDisabled = next
                    sseDisabled = next
                },
            )

            DebugLogSection(hideHeader = true)
        }
    }
}

/**
 * Hilt EntryPoint that exposes the application-wide [SettingsManager] to
 * [SettingsDebugRoute] without threading a new parameter through AppShell.
 * Mirrors the [DebugVerboseSettingsManagerEntryPoint] pattern in DebugLogSection.kt.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SseDisabledSettingsManagerEntryPoint {
    fun settingsManager(): SettingsManager
}

@Composable
private fun rememberSseDisabledSettingsManager(): SettingsManager {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            SseDisabledSettingsManagerEntryPoint::class.java,
        ).settingsManager()
    }
}
