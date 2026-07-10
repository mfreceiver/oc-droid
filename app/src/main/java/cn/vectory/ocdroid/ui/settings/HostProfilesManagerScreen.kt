package cn.vectory.ocdroid.ui.settings

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.BasicAuthConfig
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.repository.http.pemMaterialToP12
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
        HostProfileEditorDialog(
            initial = profile,
            // §item8 (cgpt#6 + grok#2): 注入「是否已存私有 CA」——clientCertId != null
            // 不等于有 CA（CA 是独立槽）。VM 直接读 ESP 的 CA 槽。
            initialHasCa = viewModel.hasStoredCa(profile.clientCertId),
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
    // §2.7: mTLS 编辑意图（纯 UI 暂存，不碰 ESP/不碰 settingsManager）。
    // §mtls-paste: 粘贴 PEM 文本（客户端证书+私钥），保存时转内部空口令 p12；
    //   下游 PKCS12 路径零改动。
    //  - mtlsEnabled：开关，种子取自 initial。
    //  - stagedClientPem：本次粘贴的客户端 PEM 文本；保存/测试时转 p12。
    //  - caStage：CA 编辑意图三态（v3-gpter R2#3）；CA 也改为粘贴 PEM 文本。
    //  - hasClientCertMaterial：派生（initial.clientCertId != null 或本次粘贴了
    //    客户端 PEM）。§item9 (cgpt#7): 重命名自 hasImportedP12——在 PEM 模型下
    //    更清晰。映射到 onSave/onTestConnection 的 hasImportedP12 位置参数（签名不变）。
    var mtlsEnabled by remember(initial.id) { mutableStateOf(initial.mtlsEnabled) }
    // §mtls-paste: 粘贴 PEM 文本（客户端证书+私钥），保存时转内部空口令 p12。
    var stagedClientPem by remember(initial.id) { mutableStateOf("") }
    var caStage: CaStage by remember(initial.id) { mutableStateOf(CaStage.Unchanged) }
    // §item9: hasClientCertMaterial 派生——既有 clientCertId 或本次粘贴了客户端 PEM。
    val hasClientCertMaterial = initial.clientCertId != null || stagedClientPem.isNotBlank()
    // §fix-3: 导入错误（PEM 解析失败等）局部回显。
    var mtlsImportError by remember(initial.id) { mutableStateOf<String?>(null) }
    // §mtls-paste: CA 文本框展示值（Replace 字节→UTF-8 文本；Unchanged/Clear→空）。
    val caPemText = (caStage as? CaStage.Replace)?.bytes?.toString(Charsets.UTF_8) ?: ""
    // §issue-5: 测试连接状态上提——触发器移入 confirmButton 的 test icon，结果
    // 回显仍在 text 列；两者共享状态，故从 Column 内部上提到 dialog 作用域。
    var testStatus by remember(initial.id) { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isTesting by remember(initial.id) { mutableStateOf(false) }
    // §C8 (ANR/perf): PKCS12 KDF 不能在主线程跑——保存/测试时把 pemMaterialToP12
    // 切到 Dispatchers.Default；isConverting 期间禁用 Save/Test 并显示进度圈。
    val scope = rememberCoroutineScope()
    var isConverting by remember(initial.id) { mutableStateOf(false) }
    // §C5 (security UX): 客户端 PEM 文本含私钥——默认以密码圆点遮蔽，eye 切换显隐。
    var showClientPem by remember(initial.id) { mutableStateOf(false) }
    // §issue-4: 分组说明改为 i 按钮点击弹窗（替代常驻描述行，省高度）。
    var showGroupInfo by remember(initial.id) { mutableStateOf(false) }

    // §item5 (cgpt#8 + grok#8): save/test 共享的 PEM→p12 转换 helper。
    // §cgpt-reval 🔴+🟠: 取快照参数 + mtlsEnabled gate。
    //   - 🔴 enabled=false（mTLS 关）时直接 null/null，不转换残留 PEM——否则用户
    //     粘了无效 PEM 后想关 mTLS 会被卡住无法保存 Disable（错误回显挡住 onSave）。
    //   - 🟠 调用方在 Main 同步取 `pemSnap = stagedClientPem` 传入；不在后台线程
    //     读 snapshot state，保证「一次保存/测试 = 点击时刻的完整表单」不撕裂。
    suspend fun convertClientPemOrError(enabled: Boolean, pem: String): Pair<ByteArray?, String?> =
        withContext(Dispatchers.Default) {
            if (!enabled || pem.isBlank()) null to null
            else runCatching { pemMaterialToP12(pem) }
                .fold({ it to null }, { null to "证书文本无效：${it.message}" })
        }

    // §issue-5: 测试连接触发逻辑抽成局部函数——原表单内全宽按钮移除，触发器改为
    // confirmButton 里的 test icon；结果回显仍在表单内。两者共享此函数。
    // §fix-401 / §fix-401-credential 语义不变：编辑已有 profile 且未改密码时回退
    // 已保存密码（write-only 字段不回填）；主动清空则按无 auth 测试。
    // §C8 (perf/ANR): PEM→p12 转换（PKCS12 KDF）切到 Dispatchers.Default；
    // §C7: 解析失败统一写到 mtlsImportError（与保存路径一致），不再走 testStatus。
    // §item6 (cgpt#9 + grok#4): try/finally 保证 isConverting 在取消/异常时也复位。
    // §cgpt-reval 🟠: Main 同步快照全部表单状态——coroutine 期间表单仍可编辑，
    //   用快照值保证「一次测试 = 点击时刻的完整表单」，不撕裂。
    fun triggerTestConnection() {
        if (isTesting || isConverting || serverUrl.isBlank()) return
        val urlSnap = serverUrl
        val userSnap = authUsername.ifBlank { null }
        val authPwSnap = authPassword.ifBlank { null }
        val insecureSnap = allowInsecure
        val pwEditedSnap = passwordEdited
        val mtlsOn = mtlsEnabled
        val pemSnap = stagedClientPem
        val caSnap = caStage
        val hasMaterial = initial.clientCertId != null || pemSnap.isNotBlank()
        val oldCertId = initial.clientCertId
        isConverting = true
        scope.launch {
            try {
                val (convertedP12, pemError) = convertClientPemOrError(mtlsOn, pemSnap)
                if (pemError != null) {
                    // §C7: 在 mTLS banner 回显（与 save 路径一致），不占用 testStatus。
                    mtlsImportError = pemError
                    return@launch
                }
                mtlsImportError = null
                isTesting = true
                testStatus = null
                // §2.7: 透传 mTLS 编辑意图（不在此构造 ClientCertMaterial——Dialog 无
                // settingsManager；由回调接收方 VM 构造）。
                onTestConnection(
                    urlSnap,
                    userSnap,
                    authPwSnap,
                    insecureSnap,
                    initial.id.takeIf { initial.basicAuth != null },
                    pwEditedSnap,
                    mtlsOn,
                    convertedP12,
                    hasMaterial,
                    caSnap,
                    null,
                    false,
                    oldCertId
                ) { success, msg ->
                    isTesting = false
                    testStatus = success to msg
                }
            } finally {
                isConverting = false
            }
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
                Spacer(modifier = Modifier.height(6.dp))
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
                Spacer(modifier = Modifier.height(6.dp))
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
                // §2.7: mTLS 区块——客户端证书（粘贴 PEM）+ 可选私有 CA。
                // §item10 (grok#10): 旧注释「PKCS12 + 密码」已改为反映 PEM 粘贴模型。
                // 互斥：开启 mTLS 强制重置 allowInsecure=false（onCheckedChange 内）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.host_mtls_title))
                        Text(
                            stringResource(R.string.host_mtls_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = mtlsEnabled,
                        onCheckedChange = {
                            mtlsEnabled = it
                            // §2.7 / glmer I7：开启 mTLS 强制重置 allowInsecure，
                            // 防关闭 mTLS 时静默降级 trust-all。
                            if (it) allowInsecure = false
                        }
                    )
                }
                if (mtlsEnabled) {
                    // §mtls-paste: 降级 banner（缺失/损坏 或 PEM 解析错误）。
                    val mtlsBanner = mtlsErrorHint ?: mtlsImportError
                    if (mtlsBanner != null) {
                        Text(
                            mtlsBanner,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    // §mtls-paste: 客户端证书+私钥 PEM 文本框（无口令）。
                    // §C5 (security UX): 该字段含私钥——默认 PasswordVisualTransformation
                    //   遮蔽，eye 切换显隐（与 basic-auth / tunnel 密码字段同模式）。
                    //   minLines=3 时遮蔽显示为每行圆点（可接受，秘密不外泄）。
                    OutlinedTextField(
                        value = stagedClientPem,
                        onValueChange = {
                            stagedClientPem = it
                            mtlsImportError = null
                        },
                        label = { Text(stringResource(R.string.host_mtls_client_pem_label)) },
                        placeholder = { Text(stringResource(R.string.host_mtls_client_pem_hint)) },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showClientPem) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showClientPem = !showClientPem }) {
                                Icon(
                                    if (showClientPem) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showClientPem) stringResource(R.string.settings_hide_password) else stringResource(R.string.settings_show_password)
                                )
                            }
                        }
                    )
                    if (!hasClientCertMaterial) {
                        Text(
                            stringResource(R.string.host_mtls_no_cert),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    // §mtls-paste: CA 证书 PEM 文本框（可选）。
                    // §item8 (cgpt#6 + grok#2): placeholder 用注入的 initialHasCa
                    //   （读 ESP 的 CA 槽），而非 initial.clientCertId != null
                    //   （后者只证明有客户端证书，不等于有 CA）。
                    OutlinedTextField(
                        value = caPemText,
                        onValueChange = { text ->
                            caStage = if (text.isBlank()) CaStage.Clear
                                      else CaStage.Replace(text.toByteArray(Charsets.UTF_8))
                        },
                        label = { Text(stringResource(R.string.host_mtls_ca_pem_label)) },
                        placeholder = {
                            Text(
                                if (caStage is CaStage.Unchanged && initialHasCa)
                                    stringResource(R.string.host_mtls_ca_pem_existing_hint)
                                else
                                    stringResource(R.string.host_mtls_ca_pem_hint)
                            )
                        },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // §item8: 移除 CA 按钮也用 initialHasCa（同 placeholder 判据）。
                    if (caStage is CaStage.Unchanged && initialHasCa || caStage is CaStage.Replace) {
                        TextButton(
                            onClick = { caStage = CaStage.Clear },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(stringResource(R.string.host_mtls_remove_ca))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
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
                        enabled = !isTesting && !isConverting && serverUrl.isNotBlank()
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
                    Button(
                        enabled = !isConverting && !isTesting,
                        onClick = {
                            if (isConverting || isTesting) return@Button
                            val basicAuth = authUsername.ifBlank { null }?.let { BasicAuthConfig(username = it, passwordId = initial.id) }
                            // #5: respect tunnelEdited so an untouched (blank)
                            // password field does NOT clear the stored
                            // tunnelPasswordId. The editor field is always seeded
                            // blank (passwords are write-only), so without this
                            // guard every save wiped the tunnel credential.
                            val tunnelId = if (tunnelEdited) {
                                tunnelPassword.ifBlank { null }?.let { initial.id }
                            } else {
                                initial.tunnelPasswordId
                            }
                            val saved = initial.copy(
                                name = name.ifBlank { "Untitled" },
                                serverUrl = serverUrl,
                                basicAuth = basicAuth,
                                tunnelPasswordId = tunnelId,
                                allowInsecureConnections = allowInsecure,
                                serverGroupFp = if (selectedGroup != initialGroup) {
                                    selectedGroup ?: initial.id
                                } else {
                                    initial.serverGroupFp
                                }
                            )
                            // If the user blanks the username on a profile that
                            // previously had basic auth, force passwordEdited so
                            // saveHostProfile removes any leftover stored password.
                            val effectivePasswordEdited = passwordEdited ||
                                (authUsername.isBlank() && initial.basicAuth != null)
                            // Positional call: onSave is a function-type parameter,
                            // so Kotlin prohibits named arguments here. Names are
                            // documented by the lambda signature instead.
                            // §2.7: 尾部透传 mTLS 编辑意图（VM 据此试构建 + 原子写 ESP）。
                            // §mtls-paste: 粘贴的客户端 PEM → 内部空口令 p12。
                            // §C8 (perf/ANR): PEM→p12 转换（PKCS12 KDF）切到
                            //   Dispatchers.Default；解析失败回显 + 中止保存。
                            // §item6 (cgpt#9 + grok#4): try/finally 保证 isConverting
                            //   在取消/异常时也复位。isConverting 在 launch 前同步置位
                            //   （按钮立即禁用）。
                            // §cgpt-reval 🟠: Main 同步快照全部可变状态——coroutine 期间
                            //   表单仍可编辑，用快照值保证「一次保存 = 点击时刻的完整表单」。
                            // §cgpt-reval 🔴: mtlsOn gate——关 mTLS 时不转换残留 PEM。
                            val mtlsOn = mtlsEnabled
                            val pemSnap = stagedClientPem
                            val caSnap = caStage
                            val hasMaterial = initial.clientCertId != null || pemSnap.isNotBlank()
                            val authPw = authPassword
                            val tunnelPw = tunnelPassword
                            val tunnelEd = tunnelEdited
                            isConverting = true
                            scope.launch {
                                try {
                                    val (convertedP12, pemError) = convertClientPemOrError(mtlsOn, pemSnap)
                                    if (pemError != null) {
                                        mtlsImportError = pemError
                                    } else {
                                        mtlsImportError = null
                                        onSave(
                                            saved,
                                            authPw,
                                            effectivePasswordEdited,
                                            tunnelPw,
                                            tunnelEd,
                                            mtlsOn,
                                            convertedP12,
                                            caSnap,
                                            null,
                                            false,
                                            hasMaterial
                                        )
                                    }
                                } finally {
                                    isConverting = false
                                }
                            }
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
