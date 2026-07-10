package cn.vectory.ocdroid.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.ui.ConnectionViewModel
import cn.vectory.ocdroid.ui.HostViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * HostProfile management sub-screen and its supporting composables: the
 * profile list row ([HostProfileRow]), the read-only detail dialog
 * ([HostProfileDetailDialog]), and the full editor form
 * ([HostProfileEditorDialog]). Reached from [SettingsScreen] via the
 * "manage profiles" action.
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
    var detailProfile by remember { mutableStateOf<HostProfile?>(null) }
    val deleteFailedText = stringResource(R.string.host_profile_delete_failed)

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.host_profiles_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            },
            actions = {
                IconButton(onClick = { editingProfile = newDirectProfile() }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.host_profile_add))
                }
            }
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("host.profile.list")
        ) {
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(12.dp))
            }
            profiles.forEach { profile ->
                HostProfileRow(
                    profile = profile,
                    selected = profile.id == currentProfileId,
                    onOpen = { detailProfile = profile },
                    onSelect = { viewModel.selectHostProfile(profile.id) },
                    onEdit = { editingProfile = profile }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
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
            onSave = { saved, basicAuthPassword, basicAuthEdited, tunnelPassword, tunnelEdited,
                       mtlsEnabled, stagedP12, caStage, p12Password, p12PasswordEdited, hasImportedP12 ->
                val clientCertEdit = if (mtlsEnabled) {
                    ClientCertEditIntent.Update(stagedP12, caStage, p12Password, p12PasswordEdited, hasImportedP12)
                } else {
                    ClientCertEditIntent.Disable
                }
                runCatching {
                    viewModel.saveHostProfile(
                        saved,
                        basicAuthPassword = basicAuthPassword,
                        basicAuthEdited = basicAuthEdited,
                        tunnelPassword = tunnelPassword,
                        tunnelEdited = tunnelEdited,
                        clientCertEdit = clientCertEdit,
                    )
                }.onSuccess { editingProfile = null }
                    .onFailure { error = it.message ?: deleteFailedText }
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
            onTestConnection = { url, user, pass, insecure, profileId, passwordEdited,
                                 mtlsEnabled, stagedP12, hasImportedP12, caStage, p12Password, p12PasswordEdited,
                                 clientCertId, callback ->
                connectionVM.testConnectionForm(
                    url, user, pass, insecure, profileId, passwordEdited,
                    mtlsEnabled, stagedP12, hasImportedP12, caStage, p12Password, p12PasswordEdited,
                    clientCertId, callback
                )
            }
        )
    }

    detailProfile?.let { profile ->
        HostProfileDetailDialog(
            profile = profile,
            isCurrent = profile.id == currentProfileId,
            onDismiss = { detailProfile = null },
            onUse = {
                viewModel.selectHostProfile(profile.id)
                detailProfile = null
            },
            onEdit = {
                editingProfile = profile
                detailProfile = null
            }
        )
    }
}

@Composable
internal fun HostProfileRow(
    profile: HostProfile,
    selected: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onEdit: () -> Unit
) {
    // A3: collapsed the hand-rolled Surface + Row layout onto the M3 ListItem
    // template (leadingContent = host icon, headlineContent = name,
    // supportingContent = server URL, trailingContent = the existing
    // RadioButton + edit affordance). The RadioButton control itself and all
    // click handlers are preserved verbatim; only the container changed.
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .testTag("host.profile.row.${profile.id}"),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        leadingContent = {
            Icon(
                Icons.Default.Dns,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = selected,
                    onClick = onSelect
                )
                // Edit: opens the editor dialog. IconButton consumes the click
                // so it does not bubble up to the row's onOpen handler.
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.host_profile_edit_icon)
                    )
                }
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

@Composable
internal fun HostProfileEditorDialog(
    initial: HostProfile,
    onDismiss: () -> Unit,
    onSave: (
        profile: HostProfile,
        basicAuthPassword: String,
        basicAuthEdited: Boolean,
        tunnelPassword: String,
        tunnelEdited: Boolean,
        // §2.7 mTLS 编辑意图（VM 据此写 ESP，原子提交；Dialog 不碰 ESP）：
        mtlsEnabled: Boolean,
        stagedP12: ByteArray?,
        caStage: CaStage,
        p12Password: String?,
        p12PasswordEdited: Boolean,
        hasImportedP12: Boolean
    ) -> Unit,
    // §item8 (cgpt#6 + grok#2): 是否已存私有 CA——由调用方从 ESP 的 CA 槽注入。
    // 比 `initial.clientCertId != null` 准确（clientCertId 只证明有客户端证书）。
    initialHasCa: Boolean = false,
    // §mtls-clipboard: 重入时已存客户端 p12 / CA 的 (subject, sizeBytes)，供槽位
    // 渲染 Imported 态。null ⇒ 该槽 Empty。纯展示，不改 resolve/save 语义。
    initialClientSummary: Pair<String, Int>? = null,
    initialCaSummary: Pair<String, Int>? = null,
    canDelete: Boolean = false,
    onDelete: () -> Unit = {},
    // §user-req: 一次性"测试连接"回调。调用方（HostProfilesManagerScreen）
    // 把 ConnectionViewModel.testConnectionForm 注入进来；Dialog 不持有 ViewModel
    // 引用，保持纯 UI 组件可测试性。默认 no-op 以兼容不关心此能力的调用方
    // （如 SettingsSectionsInstrumentedTest）。
    // §2.7: 加透传 mTLS 字段——由回调接收方 VM 构造 ClientCertMaterial（Dialog 无
    // settingsManager，不在此构造）。含 clientCertId（initial.clientCertId）——编辑既有
    // mTLS profile 且未重导 p12 时，VM 据此回 ESP 读已存 p12/密码/CA。
    onTestConnection: (
        baseUrl: String,
        username: String?,
        password: String?,
        allowInsecure: Boolean,
        profileId: String?,
        passwordEdited: Boolean,
        // §2.7 mTLS 透传字段：
        mtlsEnabled: Boolean,
        stagedP12: ByteArray?,
        hasImportedP12: Boolean,
        caStage: CaStage,
        p12Password: String?,
        p12PasswordEdited: Boolean,
        clientCertId: String?,
        onResult: (Boolean, String) -> Unit
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
    // §fix-3 (gro-1#2/gpt-2#2/max-1 M1): 当前 host 的 mTLS 降级错误（缺失/损坏），
    // 由调用方从 ConnectionState.mtlsDegradedError 注入；非 null 时在 mTLS 区块顶部
    // 显示红色 banner，让用户看到「证书加载失败」而非泛化连接失败。
    mtlsErrorHint: String? = null,
) {
    val groupLabels = NamedGroupLabels // §grouping-rewrite Round-2 #4: was a local listOf("A","B","C","D") — centralised in SettingsSections.kt so the editor + ConnectionProfileSection stats line stay in lockstep.
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var serverUrl by remember(initial.id) { mutableStateOf(initial.serverUrl) }
    var authUsername by remember(initial.id) { mutableStateOf(initial.basicAuth?.username.orEmpty()) }
    var authPassword by remember(initial.id) { mutableStateOf("") }
    var passwordEdited by remember(initial.id) { mutableStateOf(false) }
    var tunnelPassword by remember(initial.id) { mutableStateOf("") }
    var tunnelEdited by remember(initial.id) { mutableStateOf(false) }
    var showBasicPassword by remember(initial.id) { mutableStateOf(false) }
    var showTunnelPassword by remember(initial.id) { mutableStateOf(false) }
    var showDeleteConfirm by remember(initial.id) { mutableStateOf(false) }
    val initialGroup = remember(initial.id, initial.serverGroupFp) {
        initial.serverGroupFp.takeIf { it in groupLabels }
    }
    var selectedGroup by remember(initial.id, initial.serverGroupFp) { mutableStateOf(initialGroup) }
    // R-01: per-host "接受不安全连接"开关（自签证书/内网 TLS 用户需要显式启用，
    // 否则全局 strict 校验会直接拒绝连接）。种子取自当前 HostProfile。
    var allowInsecure by remember(initial.id) { mutableStateOf(initial.allowInsecureConnections) }
    // §mtls-clipboard: 折叠区开关——新 profile 全 false（§design E），既有 profile
    // 按是否配置了对应凭据种子。三个区（Basic Auth / 隧道 / mTLS）共用
    // [CollapsibleSection] 容器，关则隐藏内容并在保存时清空对应凭据。
    var basicAuthEnabled by remember(initial.id) { mutableStateOf(initial.basicAuth != null) }
    var tunnelEnabled by remember(initial.id) { mutableStateOf(initial.tunnelPasswordId != null) }
    var mtlsEnabled by remember(initial.id) { mutableStateOf(initial.mtlsEnabled) }
    // §mtls-clipboard: 客户端 p12 直接以已校验 ByteArray 暂存（剪贴板粘贴→
    // decodeBase64OrNull→loadClientP12OrNull 验证后写入）。null=未重导（沿用已存）。
    var stagedP12: ByteArray? by remember(initial.id) { mutableStateOf<ByteArray?>(null) }
    var caStage: CaStage by remember(initial.id) { mutableStateOf(CaStage.Unchanged) }
    // §mtls-clipboard: 每个导入槽的展示态。重入时从已存字节摘要种子（修 write-only
    // 空白）；粘贴成功置 Imported，失败置 Error。纯展示，不改 resolve/save 语义。
    var clientSlotStatus by remember(initial.id) {
        mutableStateOf<CertSlotStatus>(
            initialClientSummary?.let { CertSlotStatus.Imported(it.first, it.second) } ?: CertSlotStatus.Empty
        )
    }
    var caSlotStatus by remember(initial.id) {
        mutableStateOf<CertSlotStatus>(
            if (initialHasCa && initialCaSummary != null)
                CertSlotStatus.Imported(initialCaSummary.first, initialCaSummary.second)
            else CertSlotStatus.Empty
        )
    }
    // §review-3: 摘要现在异步注入（produceState，见 HostProfilesManagerScreen）。用
    // 两个布尔追踪「用户是否已动手编辑该槽」，仅当未编辑时才让晚到的摘要反应式
    // 种 Imported 态——已粘贴/已移除则尊重用户意图，不覆盖。
    var clientEdited by remember(initial.id) { mutableStateOf(false) }
    // §review-3: Clear-signal——用户「移除」一个已存客户端证书（initial.clientCertId
    // != null）时置 true；粘贴成功复位 false。hasMaterial 据此区分「UI 槽为空但 ESP
    // 里仍有旧 p12」与「真无材料」，阻止「移除→重开 mTLS→不粘贴→保存」静默重载旧
    // p12（gpter R2 BLOCK）。最小修复：无新增 onSave 形参、无 resolve 层改动。
    var clientCleared by remember(initial.id) { mutableStateOf(false) }
    var caEdited by remember(initial.id) { mutableStateOf(false) }
    LaunchedEffect(initialClientSummary) {
        // §coverage-r4: decision logic hoisted into the pure
        // [seedClientCertSlotStatus] helper. The LaunchedEffect body now
        // just maps the result to a state write — the branching (null
        // check, !clientEdited gate, Empty-slot guard) lives in the pure
        // helper, covered by [MtlsDialogCallBuildersTest].
        seedClientCertSlotStatus(initialClientSummary, clientEdited, clientSlotStatus)
            ?.let { clientSlotStatus = it }
    }
    LaunchedEffect(initialCaSummary) {
        // §coverage-r4: same split as the client summary LaunchedEffect —
        // decision logic in [seedCaSlotStatus] (pure), LaunchedEffect body
        // is a thin map-to-state-write.
        seedCaSlotStatus(initialCaSummary, caEdited, caSlotStatus, initialHasCa)
            ?.let { caSlotStatus = it }
    }
    // §review-2: 任一槽处于 Error 意味着暂存意图已过期（如失败的粘贴残留了旧的
    // caStage=Clear）。Save 和 Test 一并禁用，强迫用户先解决错误（重新粘贴成功 →
    // Imported，或显式移除 → Empty）后才能提交。
    // §review-3: 仅在 mTLS 开启时门控——关闭 mTLS 后证书槽已不相关，残留 Error
    // （如失败的粘贴）不应继续阻断提交（gpter R2 non-block #2）。
    // §coverage-r4: decision hoisted into the pure [dialogHasCertError] helper.
    // The dialog's Composable body becomes a one-liner; the branching
    // (mtlsEnabled gate + OR of the two slot statuses) is unit-testable
    // without Compose (see [MtlsDialogCallBuildersTest]).
    val hasCertError = dialogHasCertError(
        mtlsEnabled = mtlsEnabled,
        clientSlotStatus = clientSlotStatus,
        caSlotStatus = caSlotStatus,
    )
    // §review-r4 (gpter R4 #1/#2): dialog-level mTLS material check — the pure
    // [mtlsHasMaterial] predicate hoisted out of the Save onClick /
    // triggerTestConnection inline copies. Drives (a) the Save-button disable
    // when `mtlsEnabled && !hasMaterial` so the failing save can't even be
    // attempted, and (b) the inline "需先导入客户端证书" hint in the mTLS section.
    val hasMaterial = mtlsHasMaterial(clientCleared, initial.clientCertId, stagedP12)
    // §fix-3: 导入错误（解析失败等）局部回显，mTLS 区块顶部 banner。
    var mtlsImportError by remember(initial.id) { mutableStateOf<String?>(null) }
    // §issue-5: 测试连接状态上提——触发器移入 confirmButton 的 test icon，结果
    // 回显仍在 text 列；两者共享状态，故从 Column 内部上提到 dialog 作用域。
    var testStatus by remember(initial.id) { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isTesting by remember(initial.id) { mutableStateOf(false) }
    // §C8 (ANR/perf): PKCS12 KDF 不能在主线程跑——粘贴验证切 Dispatchers.Default；
    // isConverting 期间禁用 Save/Test/再次粘贴并显示进度圈。
    val scope = rememberCoroutineScope()
    var isConverting by remember(initial.id) { mutableStateOf(false) }
    // §issue-4: 分组说明改为 i 按钮点击弹窗（替代常驻描述行，省高度）。
    var showGroupInfo by remember(initial.id) { mutableStateOf(false) }

    // §mtls-clipboard: 剪贴板读取（仅在粘贴按钮点击时）。容忍纯 base64 与粘贴的
    // PEM（sanitation 在 util 层做）。
    val ctx = LocalContext.current
    val clipboard = remember(ctx) {
        ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    }
    val errBase64 = stringResource(R.string.host_cert_err_base64)
    val errP12 = stringResource(R.string.host_cert_err_p12)
    val errCa = stringResource(R.string.host_cert_err_ca)
    val pasteClientLabel = stringResource(R.string.host_cert_paste_client)
    val pasteCaLabel = stringResource(R.string.host_cert_paste_ca)
    // §review-6: role labels via string resources (were hardcoded "客户端证书" / "CA 证书").
    val roleClientLabel = stringResource(R.string.host_cert_role_client)
    val roleCaLabel = stringResource(R.string.host_cert_role_ca)

    fun readClip(): String? =
        clipboard?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()

    // §mtls-clipboard: 客户端证书粘贴——剪贴板→decode→loadClientP12OrNull 验证（后台
    // 线程）→成功取叶子证书 subject + size 写 stagedP12；失败置 Error。
    // 重活在 Dispatchers.Default 跑，状态写回在 Main（rememberCoroutineScope 默认 Main）。
    // §coverage-r4: withContext body + post-import state-write rules hoisted into
    // the pure [decodeClientP12Import] helper + the [applyClientP12ImportResult]
    // helper. The local `fun` only handles the clipboard read + scope.launch
    // scaffold; the import-result → state mapping lives in the pure helpers
    // (covered by [MtlsDialogCallBuildersTest]).
    fun triggerClientPaste() {
        if (isConverting) return
        clientEdited = true
        val raw = readClip()
        if (raw.isNullOrBlank()) {
            clientSlotStatus = CertSlotStatus.Error(errBase64)
            return
        }
        isConverting = true
        scope.launch {
            try {
                val (status, bytes) = withContext(Dispatchers.Default) {
                    decodeClientP12Import(raw, errBase64, errP12)
                }
                applyClientP12ImportResult(
                    status = status,
                    bytes = bytes,
                    setClientSlotStatus = { clientSlotStatus = it },
                    setStagedP12 = { stagedP12 = it },
                    setClientCleared = { clientCleared = it },
                    setMtlsImportError = { mtlsImportError = it },
                )
            } finally {
                isConverting = false
            }
        }
    }

    // §mtls-clipboard: CA 证书粘贴——剪贴板→decode→parseCaCertOrNull 验证（后台
    // 线程）→成功写 caStage=Replace(bytes)；失败置 Error。
    // §coverage-r4: withContext body + post-import state-write rules hoisted
    // into [decodeCaImport] + [applyCaImportResult] (same split as
    // triggerClientPaste).
    fun triggerCaPaste() {
        if (isConverting) return
        caEdited = true
        val raw = readClip()
        if (raw.isNullOrBlank()) {
            caSlotStatus = CertSlotStatus.Error(errBase64)
            return
        }
        isConverting = true
        scope.launch {
            try {
                val (status, stage) = withContext(Dispatchers.Default) {
                    decodeCaImport(raw, errBase64, errCa)
                }
                applyCaImportResult(
                    status = status,
                    stage = stage,
                    setCaSlotStatus = { caSlotStatus = it },
                    setCaStage = { caStage = it },
                )
            } finally {
                isConverting = false
            }
        }
    }

    // §issue-5: 测试连接触发逻辑抽成局部函数——原表单内全宽按钮移除，触发器改为
    // confirmButton 里的 test icon；结果回显仍在表单内。两者共享此函数。
    // §fix-401 / §fix-401-credential 语义不变：编辑已有 profile 且未改密码时回退
    // 已保存密码（write-only 字段不回填）；主动清空则按无 auth 测试。
    // §mtls-clipboard: stagedP12 已是粘贴时校验过的 ByteArray，直接透传，无需转换。
    // §cgpt-reval 🟠: Main 同步快照全部表单状态，保证「一次测试 = 点击时刻的完整表单」。
    fun triggerTestConnection() {
        if (isTesting || isConverting || serverUrl.isBlank() || hasCertError) return
        // §review-r4 (gpter R4 #3): hoisted into the pure [buildTestCall] snapshot
        // helper. Mirrors [buildSaveCall]'s split: the local `fun` only forwards the
        // [TestCallResult] to the dialog's onTestConnection callback; the
        // basicAuthEnabled / hasMaterial / hasImportedP12 / caStage passthrough rules
        // live in the pure builder (see [MtlsDialogCallBuildersTest]).
        val testResult = buildTestCall(
            initial = initial,
            serverUrl = serverUrl,
            allowInsecure = allowInsecure,
            basicAuthEnabled = basicAuthEnabled,
            authUsername = authUsername,
            authPassword = authPassword,
            passwordEdited = passwordEdited,
            mtlsEnabled = mtlsEnabled,
            clientCleared = clientCleared,
            initialClientCertId = initial.clientCertId,
            stagedP12 = stagedP12,
            caStage = caStage,
        )
        isTesting = true
        testStatus = null
        // §2.7: 透传 mTLS 编辑意图（不在此构造 ClientCertMaterial——Dialog 无
        // settingsManager；由回调接收方 VM 构造）。onTestConnection 内部走 viewModelScope。
        onTestConnection(
            testResult.url,
            testResult.userSnap,
            testResult.authPwSnap,
            testResult.insecureSnap,
            testResult.profileIdSnap,
            testResult.pwEditedSnap,
            testResult.mtlsOn,
            testResult.p12Snap,
            testResult.hasMaterial,
            testResult.caSnap,
            testResult.p12Password,
            testResult.p12PasswordEdited,
            testResult.oldCertId,
        ) { success, msg ->
            isTesting = false
            testStatus = success to msg
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) stringResource(R.string.host_profile_add_title) else stringResource(R.string.host_profile_edit_title)) },
        text = {
            // §issue-6: 内容列加 verticalScroll + heightIn 兜底——编辑表单在小屏 /
            // 大字号 / 键盘弹起时不再被裁切。叠加下述 label 槽化 + 分组/测试改造，
            // 常规尺寸下整体高度也显著下降。
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // 配置名 (required) — label 槽替代上方独立 Text（M3 idiom + 省 1 行，§issue-6）
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.host_profile_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                // 服务器地址 (required) — label 槽（§issue-6）
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text(stringResource(R.string.settings_server_url)) },
                    placeholder = { Text(stringResource(R.string.host_profile_url_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                // §mtls-clipboard / §design E: 三个凭据区折叠——新 profile 全 false，
                // 既有 profile 按是否配置种子。关则隐藏内容，保存时清空对应凭据。
                CollapsibleSection(
                    title = stringResource(R.string.host_section_basic_auth_title),
                    subtitle = stringResource(R.string.host_section_basic_auth_sub),
                    checked = basicAuthEnabled,
                    onCheckedChange = { basicAuthEnabled = it },
                ) {
                    // Username (optional) — label 槽（§issue-6）
                    OutlinedTextField(
                        value = authUsername,
                        onValueChange = { authUsername = it },
                        label = { Text(stringResource(R.string.host_profile_basic_auth_username)) },
                        placeholder = { Text(stringResource(R.string.common_optional)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    // Password (optional, masked) — label 槽（§issue-6）
                    OutlinedTextField(
                        value = authPassword,
                        onValueChange = {
                            passwordEdited = true
                            authPassword = it
                        },
                        label = { Text(stringResource(R.string.host_profile_basic_auth_password)) },
                        placeholder = {
                            // Mirror the tunnel-password field: the password is
                            // write-only (never echoed back), but show masked dots
                            // when a password is already stored so reopening the
                            // editor doesn't look like the credential vanished.
                            Text(
                                if (initial.basicAuth != null && !passwordEdited) stringResource(R.string.host_profile_password_masked_placeholder)
                                else stringResource(R.string.common_optional)
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showBasicPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showBasicPassword = !showBasicPassword }) {
                                Icon(
                                    if (showBasicPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showBasicPassword) stringResource(R.string.settings_hide_password) else stringResource(R.string.settings_show_password)
                                )
                            }
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                CollapsibleSection(
                    title = stringResource(R.string.host_section_tunnel_title),
                    subtitle = stringResource(R.string.host_section_tunnel_sub),
                    checked = tunnelEnabled,
                    onCheckedChange = { tunnelEnabled = it },
                ) {
                    // Tunnel auth (optional, masked) — label 槽（§issue-6）
                    OutlinedTextField(
                        value = tunnelPassword,
                        onValueChange = {
                            tunnelEdited = true
                            tunnelPassword = it
                        },
                        label = { Text(stringResource(R.string.host_profile_tunnel_password_label)) },
                        placeholder = {
                            // When a tunnel password is already stored for this host
                            // (and the user hasn't started editing), show masked dots
                            // so reopening the editor doesn't look like the credential
                            // vanished. The field stays write-only (the actual password
                            // is never echoed back), but the dots signal "data present".
                            Text(
                                if (initial.tunnelPasswordId != null && !tunnelEdited) stringResource(R.string.host_profile_password_masked_placeholder)
                                else stringResource(R.string.common_optional)
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showTunnelPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showTunnelPassword = !showTunnelPassword }) {
                                Icon(
                                    if (showTunnelPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showTunnelPassword) stringResource(R.string.settings_hide_password) else stringResource(R.string.settings_show_password)
                                )
                            }
                        }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                // §issue-4: 分组选择改用 M3 SingleChoiceSegmentedButtonRow（替代 5 个
                // OutlinedButton 平铺），选项名简化为「独立 / A / B / C / D」；常驻
                // 描述行移除，改为标题右侧 i 按钮点击弹窗（§issue-6 省高度）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.host_group_label),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { showGroupInfo = true }) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.host_group_info),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val groupOptions = listOf<Pair<String?, String>>(
                        null to stringResource(R.string.host_group_none),
                        "A" to "A",
                        "B" to "B",
                        "C" to "C",
                        "D" to "D"
                    )
                    groupOptions.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = selectedGroup == value,
                            onClick = { selectedGroup = value },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = groupOptions.size),
                            icon = {}
                        ) {
                            Text(label, maxLines = 1)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                // R-01: per-host insecure-connections toggle. Off by default
                // (strict TLS); enabling it downgrades this host's REST/SSE/
                // health/tunnel clients to trust-all so self-signed or internal
                // TLS servers can connect. The warning makes the MITM risk
                // explicit at the point of enablement.
                // §2.7 (glmer I7): 与 mTLS 互斥——开启 mTLS 时禁用本开关，防日后
                // 关 mTLS 时静默降级 trust-all（mTLS 优先级也由 SslConfigFactory 保证）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.host_allow_insecure_title))
                        Text(
                            stringResource(R.string.host_allow_insecure_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = allowInsecure,
                        onCheckedChange = { allowInsecure = it },
                        enabled = !mtlsEnabled
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                // §mtls-clipboard: mTLS 区块——折叠区容器（与 Basic Auth / 隧道一致），
                // 内容为客户端证书 + CA 两个剪贴板导入槽。互斥：开启 mTLS 强制重置
                // allowInsecure=false（onCheckedChange 内，glmer I7）。
                CollapsibleSection(
                    title = stringResource(R.string.host_mtls_title),
                    subtitle = stringResource(R.string.host_mtls_summary),
                    checked = mtlsEnabled,
                    onCheckedChange = {
                        mtlsEnabled = it
                        // §2.7 / glmer I7：开启 mTLS 强制重置 allowInsecure，
                        // 防关闭 mTLS 时静默降级 trust-all。
                        if (it) allowInsecure = false
                    },
                ) {
                    // §mtls-clipboard: 降级 banner（缺失/损坏 或粘贴解析错误）。
                    val mtlsBanner = mtlsErrorHint ?: mtlsImportError
                    if (mtlsBanner != null) {
                        Text(
                            mtlsBanner,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    // §review-r4 (gpter R4 #1): mTLS on but no usable client-cert
                    // material (new profile never pasted, or existing cert removed
                    // via clientCleared). Surface the reason inline here so the
                    // disabled Save button is never a mystery — mirrors the
                    // mtlsBanner/mtlsImportError style above.
                    if (mtlsEnabled && !hasMaterial) {
                        Text(
                            stringResource(R.string.host_mtls_missing_client_cert),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    CertImportSlot(
                        roleLabel = roleClientLabel,
                        status = clientSlotStatus,
                        onPaste = { triggerClientPaste() },
                        onRemove = {
                            // §review-1: 对已有客户端证书的 profile（initial.clientCertId
                            // != null），仅清 stagedP12 + status 是假删除——hasMaterial 仍
                            // 为 true，Save 会从 ESP 重载旧 p12。「移除已存客户端证书」的
                            // 唯一合理含义是「关闭 mTLS」→ mtlsEnabled=false 折叠区块，
                            // Save 产 ClientCertEditIntent.Disable，ESP 里的证书被清。对新
                            // 粘贴（clientCertId == null）只需清暂存粘贴。
                            clientEdited = true
                            if (initial.clientCertId != null) {
                                mtlsEnabled = false
                                // §review-3: 置 Clear-signal——后续 hasMaterial 不再认 ESP
                                // 里的旧 p12，阻止「重开 mTLS→不粘贴→保存」静默重载它。
                                clientCleared = true
                            }
                            stagedP12 = null
                            clientSlotStatus = CertSlotStatus.Empty
                            mtlsImportError = null
                        },
                        pasteLabel = pasteClientLabel,
                        enabled = !isConverting,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    CertImportSlot(
                        roleLabel = roleCaLabel,
                        status = caSlotStatus,
                        onPaste = { triggerCaPaste() },
                        onRemove = {
                            caEdited = true
                            caStage = CaStage.Clear
                            caSlotStatus = CertSlotStatus.Empty
                        },
                        pasteLabel = pasteCaLabel,
                        enabled = !isConverting,
                    )
                }
                // §issue-5: 全宽"测试连接"按钮已移除——触发器移入底部 action 行的
                // test icon（见 confirmButton）。此处仅保留结果回显（成功/失败小字），
                // 测试进行中由 icon 内的进度圈表达。
                testStatus?.let { (success, msg) ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                // §fix-401: 旧提示"编辑已有配置时请重新输入密码后再测试"已移除——
                // 现在 VM 会在未编辑密码时自动回退已保存密码，无需用户重输。
            }
        },
        // Bottom action row: [Test][Delete] ... [Cancel] [Save].
        // §issue-5: 测试连接从表单全宽按钮迁来此处的 test icon（NetworkCheck；
        // 测试中换进度圈、URL 空或测试中禁用）。删除键改为 icon-only（去文字标签），
        // 保留 error 着色 + contentDescription 供无障碍。两 icon 组成左簇。
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { triggerTestConnection() },
                        enabled = !isTesting && !isConverting && serverUrl.isNotBlank() && !hasCertError
                    ) {
                        if (isTesting || isConverting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.NetworkCheck,
                                contentDescription = stringResource(R.string.host_profile_test_connection),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (canDelete) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.common_delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    // §item7 (glm#4): Save 同时受 isTesting 门控——防测试进行中点 Save
                    //   取消在飞的测试协程。
                    // §review-2: 另受 hasCertError 门控——任一证书槽处于 Error 时
                    //   暂存意图已过期，禁止提交。
                    // §review-r4 (gpter R4 #1): 另受 mtlsEnabled && !hasMaterial 门控——
                    //   mTLS 开启但无可用客户端证书材料时禁用 Save，使原本会抛「需先导入
                    //   客户端证书」（错误回显在底层屏幕、被对话框遮挡，看起来像「点了没
                    //   反应」）的保存根本无法发起。
                    Button(
                        enabled = !isConverting && !isTesting && !hasCertError && !(mtlsEnabled && !hasMaterial),
                        onClick = {
                            if (isConverting || isTesting || hasCertError || (mtlsEnabled && !hasMaterial)) return@Button
                            // §review-r4 (gpter R4 #3): Save / Test snapshot/branching
                            // logic hoisted into pure top-level [buildSaveCall] /
                            // [buildTestCall]. The onClick body now just assembles
                            // inputs and forwards the [SaveCallResult] / [TestCallResult]
                            // to the dialog's onSave / onTestConnection callbacks. The
                            // pure builders are unit-testable without spinning up
                            // Compose (see [MtlsDialogCallBuildersTest]).
                            val saveResult = buildSaveCall(
                                initial = initial,
                                name = name,
                                serverUrl = serverUrl,
                                allowInsecure = allowInsecure,
                                selectedGroup = selectedGroup,
                                initialGroup = initialGroup,
                                basicAuthEnabled = basicAuthEnabled,
                                authUsername = authUsername,
                                authPassword = authPassword,
                                passwordEdited = passwordEdited,
                                tunnelEnabled = tunnelEnabled,
                                tunnelPassword = tunnelPassword,
                                tunnelEdited = tunnelEdited,
                                mtlsEnabled = mtlsEnabled,
                                clientCleared = clientCleared,
                                stagedP12 = stagedP12,
                                caStage = caStage,
                            )
                            // onSave is a function-type parameter, Kotlin 禁止具名实参
                            // (position order matches the lambda signature).
                            onSave(
                                saveResult.saved,
                                saveResult.authPw,
                                saveResult.effectivePasswordEdited,
                                saveResult.tunnelPw,
                                saveResult.tunnelEd,
                                saveResult.mtlsOn,
                                saveResult.stagedP12,
                                saveResult.caStage,
                                saveResult.p12Password,
                                saveResult.p12PasswordEdited,
                                saveResult.hasMaterial,
                            )
                        }
                    ) {
                        if (isConverting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(R.string.settings_save))
                        }
                    }
                }
            }
        }
    )

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.host_profile_delete_confirm_title)) },
            text = { Text(stringResource(R.string.host_profile_delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    // §issue-4: 分组说明气泡（i 按钮触发）。原常驻描述行移除此弹窗，省表单高度。
    if (showGroupInfo) {
        AlertDialog(
            onDismissRequest = { showGroupInfo = false },
            title = { Text(stringResource(R.string.host_group_label)) },
            text = { Text(stringResource(R.string.host_group_warning)) },
            confirmButton = {
                TextButton(onClick = { showGroupInfo = false }) {
                    Text(stringResource(R.string.common_ok))
                }
            }
        )
    }
}

private fun newDirectProfile(): HostProfile = HostProfile.defaultDirect()

/**
 * §2.7: mTLS 编辑对话框中"CA 编辑意图"的三态显式表达（v3-gpter R2#3）。
 *
 * `ByteArray?` 的 null 无法区分"未改 / 清除 / 无 CA"三种语义 → 可静默从私有 CA
 * 降级平台 CA。本 sealed interface 把意图钉死，由 VM 据此解析生效 CA：
 *  - [Unchanged]：保持已存 CA（编辑既有 mTLS profile 的默认）。
 *  - [Replace]：本次导入了新 CA 字节。
 *  - [Clear]：显式移除 CA → 转平台 CA 模式。
 *
 * Dialog 仅暂存此状态（纯 UI，不碰 ESP/不碰 settingsManager）；[resolveClientCert]
 * 据其 + 已存材料归一为生效 CA。`public` 因被 public VM 函数（saveHostProfile /
 * testConnectionForm）签名引用。
 */
sealed interface CaStage {
    data object Unchanged : CaStage
    data class Replace(val bytes: ByteArray) : CaStage
    data object Clear : CaStage
}
