package cn.vectory.ocdroid.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.ui.ConnectionViewModel
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.ui.HostProfileSaveState
import cn.vectory.ocdroid.ui.theme.AppSectionHeader
import cn.vectory.ocdroid.ui.theme.Dimens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * HostProfile management sub-screen and its supporting composables: the
 * profile list row ([HostProfileRow]) and the read-only detail dialog
 * ([HostProfileDetailDialog]). The full editor form
 * ([HostProfileEditorDialog], + CaStage + cert-row helpers) was split out
 * into its own file (L5b). Reached from [SettingsScreen] via the
 * "manage profiles" action.
 *
 * §P5b-A / Q7: this screen is now the 服务器管理 hub — in addition to the
 * host list (服务器配置) it also carries 流量统计 (moved from settings/storage)
 * and 模型管理 ([ModelManagementSection], moved from the removed top-level
 * 模型 Settings entry). The model-management subscriptions
 * (providers + disabledModels) are read off [HostViewModel.settingsFlow];
 * toggle actions route through [HostViewModel.toggleModelDisabled] /
 * [HostViewModel.setProviderModelsEnabled] so the prefs write and the
 * settingsFlow mirror stay in sync (§14 — fixes a stale Switch bug where the
 * old direct `settingsManager` path only touched encrypted prefs).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostProfilesManagerScreen(
    viewModel: HostViewModel,
    connectionVM: ConnectionViewModel,
    profiles: List<HostProfile>,
    currentProfileId: String?,
    onBack: () -> Unit
) {
    var editingProfile by remember { mutableStateOf<HostProfile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val deleteFailedText = stringResource(R.string.host_profile_delete_failed)
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
                    error = it.message ?: deleteFailedText
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

    // §WT5: the host manager screen now uses the shared SettingsSubRouteScaffold
    // (same shell as every other settings sub-route) instead of a hand-rolled
    // Column+TopAppBar. The add-host IconButton is preserved via the scaffold's
    // `actions` slot. The list Column keeps its verticalScroll + testTag.
    SettingsSubRouteScaffold(
        // §setux-unify: hub 标题改用与一级入口相同的短文案（「服务器」），与
        // 外观/通知/关于三项保持「入口名 = 页面名」一致。
        titleRes = R.string.setux_settings_hosts_entry,
        onBack = onBack,
        actions = {
            IconButton(onClick = { editingProfile = newDirectProfile() }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.host_profile_add))
            }
        },
    ) { scaffoldMod ->
        // §review-AB: no parent horizontal padding — AppSectionHeader +
        // ListItem (HostProfileRow / TrafficSection) self-pad at 16dp; the
        // bare error Text below self-pads via `Modifier.padding(horizontal =
        // Dimens.spacing4)` so it shares one keyline with the header / rows.
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

            // ── §P5b-A / Q7 Section 1: 服务器配置 ──
            // §setux #5: 已配置服务器列表项间距压缩——ListItem 自带 padding，
            // 再叠 8dp Spacer 过松；降到 2dp 让列表更紧凑。
            // §WT5: header now uses AppSectionHeader (titleSmall + onSurfaceVariant,
            // canonical per docs/specs/ui-style-spec.md §2). HostProfileRow itself is
            // untouched — its RadioButton+Edit affordances stay distinct.
            AppSectionHeader(text = stringResource(R.string.host_profiles_title))
            profiles.forEach { profile ->
                HostProfileRow(
                    profile = profile,
                    selected = profile.id == currentProfileId,
                    onSelect = { viewModel.selectHostProfile(profile.id) },
                    onEdit = { editingProfile = profile }
                )
                // §setux #5: 2dp tighter inter-row gap (no Dimens token for 2dp;
                // spec §3 tolerates this one-off literal with a written reason).
                Spacer(modifier = Modifier.height(2.dp))
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
        // §mtls-followup (gpt-2): connectionFlow.mtlsDegradedError 反映的是「当前 active
        // host」的降级态。仅当编辑的就是当前主机（profile.id == currentProfileId）时才把
        // 该 hint 传入对话框；否则传 null——避免在编辑非当前 host 时误显示别的主机的
        // 降级 banner（那不是用户正在编辑的这个 host 的状态）。
        val mtlsErrorHint =
            if (profile.id == currentProfileId) connectionState.mtlsDegradedError else null
        // §review-3: clientCertSummary() runs PKCS12 KDF — must NOT run on the Compose
        // main thread. Compute both summaries off-main via produceState on
        // Dispatchers.Default; the dialog receives null initially and its slot
        // status seeds reactively (LaunchedEffect) once the value arrives. The
        // HostViewModel funcs stay as-is — the Default context makes them main-safe.
        // §coverage-r4: withContext body hoisted into the pure
        // [summarizeClientCertOnDefault] / [summarizeCaOnDefault] helpers —
        // the produceState bodies are now thin one-liners, shrinking the
        // `initialClientSummary$2$1$1` / `initialCaSummary$2$1$1` inner
        // classes (currently the only `ui/.../settings` denominator that
        // does not benefit from any JVM unit-test coverage).
        val initialClientSummary by produceState<Pair<String, Int>?>(initialValue = null, profile.clientCertId) {
            value = summarizeClientCertOnDefault(viewModel, profile.clientCertId)
        }
        val initialCaSummary by produceState<Pair<String, Int>?>(initialValue = null, profile.clientCertId) {
            value = summarizeCaOnDefault(viewModel, profile.clientCertId)
        }
        HostProfileEditorDialog(
            initial = profile,
            // §item8 (cgpt#6 + grok#2): 注入「是否已存私有 CA」——clientCertId != null
            // 不等于有 CA（CA 是独立槽）。VM 直接读 ESP 的 CA 槽。
            initialHasCa = viewModel.hasStoredCa(profile.clientCertId),
            // §mtls-clipboard: 重入时把已存 p12/CA 的 subject+size 注入，使槽位
            // 渲染 Imported 态而非空白（修「write-only 字段重入显示空」）。
            // §review-3: 现在异步注入（produceState）——初值为 null，到达后由
            // Dialog 内的 LaunchedEffect 反应式种槽。
            initialClientSummary = initialClientSummary,
            initialCaSummary = initialCaSummary,
            // The "+" action creates a fresh profile that isn't persisted yet,
            // so it must not expose the destructive delete affordance.
            canDelete = profiles.any { it.id == profile.id } && profiles.size > 1,
            onDismiss = { editingProfile = null },
            mtlsErrorHint = mtlsErrorHint,
            // §2.7 fix-3: onSave 透传 mTLS 编辑意图给 VM（Dialog 纯 UI，不碰 ESP）。VM
            // 据此试构建 + 原子写 ESP；失败（无 p12 / 试构建失败）抛异常 → 保留
            // 对话框并回显错误，不关闭。Dialog 据 mTLS 开关构造 Update / Disable intent。
            // §C-D3 rev-3 round-7 (review I5-R7): viewModel.saveHostProfile is now
            // non-suspend — the VM owns the launch (viewModelScope survives screen
            // navigation; the reconfigure transaction must complete once begun).
            // The screen observes saveState via LaunchedEffect above (close on
            // success + profileId match, error on failure) and gates the Save
            // button via isSaving (single-flight — double-submit ignored).
            onSave = { saved, basicAuthPassword, basicAuthEdited, tunnelPassword, tunnelEdited,
                       mtlsEnabled, slimEnabled, stagedP12, caStage, p12Password, p12PasswordEdited, hasImportedP12 ->
                val clientCertEdit = if (mtlsEnabled) {
                    ClientCertEditIntent.Update(stagedP12, caStage, p12Password, p12PasswordEdited, hasImportedP12)
                } else {
                    ClientCertEditIntent.Disable
                }
                viewModel.saveHostProfile(
                    saved,
                    basicAuthPassword = basicAuthPassword,
                    basicAuthEdited = basicAuthEdited,
                    tunnelPassword = tunnelPassword,
                    tunnelEdited = tunnelEdited,
                    clientCertEdit = clientCertEdit,
                )
            },
            onDelete = {
                runCatching { viewModel.deleteHostProfile(profile.id) }
                    .onFailure { error = it.message ?: deleteFailedText }
                editingProfile = null
            },
            // §user-req + §fix-401 + §2.7: 表单"测试连接"按钮直连 ConnectionViewModel.testConnectionForm。
            // 密码 write-only 不回填表单；编辑已有 host 且未碰密码框时 VM 据 profileId
            // 回退查已保存密码。用户主动清空/改密码（passwordEdited=true）则按表单值测，
            // 不回退旧凭据（安全）。§2.7: mTLS 字段透传给 VM，由 VM 构造 ClientCertMaterial
            // （Dialog 无 settingsManager）→ checkHealthFor(..., clientCert)。
            // §tofu R2: allowInsecure 不再透传——TOFU 取代 trust-all 降级；self-signed
            // endpoint 在 checkHealthFor 失败时由 coordinator 捕获 leaf 证书并弹 TOFU 对话框。
            onTestConnection = { url, user, pass, profileId, passwordEdited,
                                 mtlsEnabled, stagedP12, hasImportedP12, caStage, p12Password, p12PasswordEdited,
                                 clientCertId, callback ->
                connectionVM.testConnectionForm(
                    url, user, pass, profileId, passwordEdited,
                    mtlsEnabled, stagedP12, hasImportedP12, caStage, p12Password, p12PasswordEdited,
                    clientCertId, slim = profile.slim, onResult = callback,
                )
            },
            // §reconcile: 从函数级 connectionState 注入 slimapi 版本自检三元组
            // （dialog 不再持有 connectionVM，参数化保 fix-12 的 UX 意图——
            // 版本不兼容时阻塞对话框）。
            slimapiVersionIncompatible = connectionState.slimapiVersionIncompatible,
            // §C-D3 rev-3 round-7 (review I5-R7): gate the Save button + show
            // spinner while a save transaction is in flight (single-flight).
            isSaving = isSaving,
        )
    }

    // §P5b-A / Q7: the row-click detail popup (HostProfileDetailDialog) is no
    // longer invoked from the row — selection moved to the leading RadioButton
    // and editing to the trailing Edit IconButton. The composable definition
    // is retained below because [SettingsSectionsInstrumentedTest] still
    // references it.
}

@Composable
internal fun HostProfileRow(
    profile: HostProfile,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit
) {
    // §P5b-A / Q7 refactor: RadioButton moved to leadingContent (was
    // trailing), the surfaceVariant containerColor is dropped (default
    // container), the leading Dns icon is removed (RadioButton takes the
    // leading slot), and the whole-row clickable{ onOpen() } is removed
    // (the row is no longer clickable — selection happens via the RadioButton,
    // editing via the trailing Edit IconButton). The headline (display name)
    // + supporting (server URL) texts are kept so the user still sees which
    // server a radio selects.
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("host.profile.row.${profile.id}"),
        leadingContent = {
            RadioButton(
                selected = selected,
                onClick = onSelect,
            )
        },
        headlineContent = {
            Text(
                profile.displayName,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        supportingContent = {
            Text(
                profile.serverUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = {
            // Edit: opens the editor dialog.
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.host_profile_edit_icon)
                )
            }
        }
    )
}

@Composable
internal fun HostProfileDetailDialog(
    profile: HostProfile,
    isCurrent: Boolean,
    onDismiss: () -> Unit,
    onUse: () -> Unit,
    onEdit: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(profile.displayName) },
        text = {
            Column {
                Text(profile.serverUrl, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(
                        R.string.host_profile_status,
                        if (isCurrent) stringResource(R.string.host_profile_current) else stringResource(R.string.host_profile_saved)
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (!isCurrent) {
                    Button(onClick = onUse) { Text(stringResource(R.string.host_profile_use_this_host)) }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                TextButton(onClick = onEdit) { Text(stringResource(R.string.common_edit)) }
            }
        }
    )
}




private fun newDirectProfile(): HostProfile = HostProfile.defaultDirect()

