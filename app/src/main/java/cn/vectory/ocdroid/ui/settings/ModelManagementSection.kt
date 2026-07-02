package cn.vectory.ocdroid.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.ConfigProvider
import cn.vectory.ocdroid.data.model.ProvidersResponse

/**
 * §model-selection: Settings → Model management section. Lists every model
 * the server returned via GET /config/providers, grouped by provider, each
 * with an enable/disable switch. Disabled models are hidden from the chat
 * quick-switch picker but remain listed here so the user can re-enable them.
 *
 * Per-baseUrl persistence lives in
 * [cn.vectory.ocdroid.util.SettingsManager]; the caller projects the active
 * host's disabled set into [disabledModels] and routes toggles through
 * [onToggleModelDisabled]. Entries are keyed `"$providerId/$modelId"`.
 */
@Composable
internal fun ModelManagementSection(
    providers: ProvidersResponse?,
    disabledModels: Set<String>,
    onToggleModelDisabled: (providerId: String, modelId: String) -> Unit
) {
    SectionHeader(title = stringResource(R.string.settings_model_management))
    Text(
        stringResource(R.string.settings_model_management_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(12.dp))

    val catalog = providers?.providers.orEmpty().filter { it.models.isNotEmpty() }
    if (catalog.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text(
                stringResource(R.string.settings_model_management_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
        ) {
            catalog.forEachIndexed { providerIndex, provider ->
                if (providerIndex > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                }
                ProviderRow(
                    provider = provider,
                    disabledModels = disabledModels,
                    onToggleModelDisabled = onToggleModelDisabled
                )
            }
        }
    }
}

@Composable
private fun ProviderRow(
    provider: ConfigProvider,
    disabledModels: Set<String>,
    onToggleModelDisabled: (providerId: String, modelId: String) -> Unit
) {
    Column {
        Text(
            provider.name?.takeIf { it.isNotEmpty() } ?: provider.id,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        provider.models.forEach { (modelId, model) ->
            ModelRow(
                providerId = provider.id,
                modelId = modelId,
                displayName = model.name ?: modelId,
                enabled = "$provider.id/$modelId" !in disabledModels,
                onToggle = { onToggleModelDisabled(provider.id, modelId) }
            )
        }
    }
}

@Composable
private fun ModelRow(
    providerId: String,
    modelId: String,
    displayName: String,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "$providerId/$modelId",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = enabled, onCheckedChange = { onToggle() })
    }
}
