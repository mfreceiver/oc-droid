package cn.vectory.ocdroid.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.ui.ConnectionViewModel
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.ui.HostProfileSaveState
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.resolveMessage
import cn.vectory.ocdroid.ui.showTimed
import cn.vectory.ocdroid.ui.theme.AppSectionHeader
import cn.vectory.ocdroid.ui.theme.Dimens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * HostProfile management sub-screen. After v5lean L8 (single-host
 * simplification) there is exactly one host profile — displayed as a
 * single card with an Edit affordance that opens [HostProfileEditorDialog].
 * The multi-host list + RadioButton selection + host-switch FSM are removed.
 *
 * Keeps: 流量统计 ([TrafficSection]), 模型管理 ([ModelManagementSection]),
 * 清除数据 ([DangerZoneSection]).
 *
 * Reached from [SettingsScreen] via the "manage profiles" action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostProfilesManagerScreen(
    viewModel: HostViewModel,
    connectionVM: ConnectionViewModel,
    currentProfile: HostProfile?,
    onBack: () -> Unit
) {
    var editingProfile by remember { mutableStateOf<HostProfile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // C-D3 rev-3 round-7 (review I5-R7): the save transaction's lifecycle is
    // owned by the VM (viewModelScope — survives screen navigation, holds the
    // in-flight reconfigure so it MUST complete once begun). Observe here to
    // drive the dialog close + the Save button's isSaving gate.
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()
    val isSaving = saveState is HostProfileSaveState.Saving
    // Handle Done: close the dialog only if the user is still editing THIS
    // profile (dismiss/reopen-of-another-profile must not be closed by a
    // stale completion). On failure, surface the error and keep the dialog
    // open. Either way, consume the state so a later retry is accepted.
    LaunchedEffect(saveState) {
        val s = saveState
        if (s is HostProfileSaveState.Done) {
            s.result.onSuccess {
                if (editingProfile?.id == s.profileId) editingProfile = null
            }.onFailure {
                // M2 (post-release polish): symmetric profileId guard — a stale
                // failure from a dismissed profile (A) must not surface while
                // the user has moved to editing a different profile (B). The
                // success path already carries this guard; failure now matches.
                if (editingProfile?.id == s.profileId) {
                    error = it.message
                }
            }
            viewModel.consumeSaveState()
        }
    }

    // §P5b-A / Q7: 流量统计 subscriptions — moved verbatim from the old
    // settings/storage route. The TrafficSection composable + reset path
    // are unchanged.
    val traffic by connectionVM.trafficFlow.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { connectionVM.refreshTrafficStats() }

    // §P5b-A / Q7: 模型管理 subscriptions — providers + disabledModels live
    // on the settings slice, which HostViewModel already exposes (same store
    // as SettingsViewModel; distinctUntilChanged keeps this screen from
    // recomposing on unrelated settings churn).
    val providers by remember { viewModel.settingsFlow.map { it.providers }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = null)
    val disabledModels by remember { viewModel.settingsFlow.map { it.disabledModels }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = emptySet())
    // §需求13: model-catalog loading flag for the Model management refresh
    // IconButton on this screen. Same projection as SettingsModelsRoute.
    val isLoadingProviders by remember { viewModel.settingsFlow.map { it.isLoadingProviders }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = false)

    // §需求13 rev-7 #3: snackbar consumer for UiEvent.Error. ChatScaffold is
    // the ONLY collector of the shared uiEvents bus — when the user navigates
    // INTO this host-manager screen (different NavHost destination), a model-
    // refresh failure would emit UiEvent.Error but nobody shows a snackbar →
    // invisible failure. This collector mirrors ChatScaffold.kt:709's pattern
    // so the model_management_refresh_failed error surfaces here too.
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            if (event is UiEvent.Error) {
                snackbarHostState.showTimed(
                    message = event.resolveMessage(context),
                    durationMillis = 3_000L,
                )
            }
        }
    }

    // §L8: no add-host action — single host, edited via the card's Edit button.
    SettingsSubRouteScaffold(
        titleRes = R.string.setux_settings_hosts_entry,
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { scaffoldMod ->
        Column(
            modifier = scaffoldMod
                .verticalScroll(rememberScrollState())
                .testTag("host.profile.list")
        ) {
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.spacing4),
                )
                Spacer(modifier = Modifier.height(Dimens.spacing3))
            }

            // ── §L8 Section 1: 服务器配置 (single-host card) ──
            // §P5b-A / Q7 / §setux: header uses AppSectionHeader.
            // Multi-host list (HostProfileRow) replaced by a single card
            // showing the current profile's name + URL + Edit button.
            AppSectionHeader(text = stringResource(R.string.host_profiles_title))
            if (currentProfile != null) {
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("host.profile.row.${currentProfile.id}"),
                    headlineContent = {
                        Text(
                            currentProfile.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    supportingContent = {
                        Text(
                            currentProfile.serverUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { editingProfile = currentProfile }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(R.string.host_profile_edit_icon),
                            )
                        }
                    },
                )
            } else {
                Text(
                    stringResource(R.string.server_dialog_no_hosts),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.spacing4),
                )
            }
            Spacer(modifier = Modifier.height(Dimens.spacing4))

            // ── §P5b-A / Q7 Section 2: 流量统计 (moved from settings/storage) ──
            // §WT5: header migrated to AppSectionHeader; TrafficSection's row
            // headline was promoted bodyMedium → bodyLarge (see SettingsSections.kt).
            AppSectionHeader(text = stringResource(R.string.settings_traffic))
            TrafficSection(
                sent = traffic.trafficSent,
                received = traffic.trafficReceived,
                resetAt = traffic.resetAt,
                onReset = connectionVM::resetTrafficStats,
                hideHeader = true,
            )
            Spacer(modifier = Modifier.height(Dimens.spacing4))

            // ── §P5b-A / Q7 Section 3: 模型管理 (moved from removed top-level 模型) ──
            // §14: toggle callbacks now route through HostViewModel so the prefs
            // write and the settingsFlow mirror stay in sync (previously the
            // direct settingsManager.setModelDisabled call only touched prefs →
            // Switch state read off settingsFlow.disabledModels never updated).
            // fp resolution + the old `currentFp ?: return` silent-fail path
            // also moved into the VM (see HostViewModel.toggleModelDisabled).
            ModelManagementSection(
                providers = providers,
                disabledModels = disabledModels,
                isLoadingProviders = isLoadingProviders,
                onRefreshProviders = { viewModel.refreshProviders() },
                onToggleModelDisabled = { providerId, modelId ->
                    viewModel.toggleModelDisabled(providerId, modelId)
                },
                onSetProviderModelsEnabled = { providerId, enabled ->
                    viewModel.setProviderModelsEnabled(providerId, enabled)
                },
            )

            // ── §Q3 Section 4: 清除数据 (moved from the removed settings/storage) ──
            // 清除是全局动作（SettingsManager.clearAllLocalData via
            // resetLocalDataAndResync），与具体服务器无关，因此从原 storage
            // 入口搬到服务器管理页末段。复用现有 DangerZoneSection（hideHeader，
            // 由本段自带的 AppSectionHeader 承担标题）。
            Spacer(modifier = Modifier.height(Dimens.spacing4))
            AppSectionHeader(text = stringResource(R.string.settings_danger_zone))
            DangerZoneSection(
                onClearLocalData = viewModel::resetLocalDataAndResync,
                hideHeader = true,
            )
        }
    }

    editingProfile?.let { profile ->
        // §fix-3: 把当前 host 的 mTLS 降级错误注入 Dialog banner（connectionFlow 反应式）。
        val connectionState by connectionVM.connectionFlow.collectAsState()
        // §L8: single host — editing profile is always the current host,
        // so mtlsErrorHint always reflects the active connection's degraded state.
        val mtlsErrorHint = connectionState.mtlsDegradedError
        val initialClientSummary by produceState<Pair<String, Int>?>(initialValue = null, profile.clientCertId) {
            value = summarizeClientCertOnDefault(viewModel, profile.clientCertId)
        }
        val initialCaSummary by produceState<Pair<String, Int>?>(initialValue = null, profile.clientCertId) {
            value = summarizeCaOnDefault(viewModel, profile.clientCertId)
        }
        HostProfileEditorDialog(
            initial = profile,
            initialHasCa = viewModel.hasStoredCa(profile.clientCertId),
            initialClientSummary = initialClientSummary,
            initialCaSummary = initialCaSummary,
            // §L8: single host — delete affordance removed (canDelete=false hides the button).
            canDelete = false,
            onDismiss = { editingProfile = null },
            mtlsErrorHint = mtlsErrorHint,
            onSave = { saved, basicAuthPassword, basicAuthEdited,
                       mtlsEnabled, slimEnabled, trustAllEnabled, stagedP12, caStage, p12Password, p12PasswordEdited, hasImportedP12 ->
                val clientCertEdit = if (mtlsEnabled) {
                    ClientCertEditIntent.Update(stagedP12, caStage, p12Password, p12PasswordEdited, hasImportedP12)
                } else {
                    ClientCertEditIntent.Disable
                }
                viewModel.saveHostProfile(
                    saved,
                    basicAuthPassword = basicAuthPassword,
                    basicAuthEdited = basicAuthEdited,
                    clientCertEdit = clientCertEdit,
                )
            },
            onTestConnection = { url, user, pass, profileId, passwordEdited,
                                 mtlsEnabled, trustAllEnabled, stagedP12, hasImportedP12, caStage, p12Password, p12PasswordEdited,
                                 clientCertId, callback ->
                connectionVM.testConnectionForm(
                    url, user, pass, profileId, passwordEdited,
                    mtlsEnabled, stagedP12, hasImportedP12, caStage, p12Password, p12PasswordEdited,
                    clientCertId, slim = profile.slim, trustAll = trustAllEnabled, onResult = callback,
                )
            },
            slimapiVersionIncompatible = connectionState.slimapiVersionIncompatible,
            isSaving = isSaving,
        )
    }
}



