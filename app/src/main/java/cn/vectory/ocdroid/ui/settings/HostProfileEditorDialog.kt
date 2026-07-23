package cn.vectory.ocdroid.ui.settings

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.ui.theme.AppConfirmDialog
import cn.vectory.ocdroid.ui.theme.AppFormDialog
import cn.vectory.ocdroid.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
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
        slimEnabled: Boolean,
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
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
    // §fix-3 (gro-1#2/gpt-2#2/max-1 M1): 当前 host 的 mTLS 降级错误（缺失/损坏），
    // 由调用方从 ConnectionState.mtlsDegradedError 注入；非 null 时在 mTLS 区块顶部
    // 显示红色 banner，让用户看到「证书加载失败」而非泛化连接失败。
    mtlsErrorHint: String? = null,
    // §R8 slim-mode M2 自检（fix-12 UX）：版本不兼容时阻塞对话框。fail-closed：
    // 当 ServerCompatProfile.isSlimapiClientAccepted() == false 时，由调用方从
    // ConnectionState.slimapiVersionIncompatible 注入 (clientVer, minVer, maxVer)
    // 三元组；非 null 时弹 AlertDialog 提示用户升级。dialog 内部不再持有
    // ConnectionViewModel 引用（保持纯 UI 可测）。
    slimapiVersionIncompatible: Triple<Int, Int, Int>? = null,
    // C-D3 rev-3 round-7 (review I5-R7): isSaving gate for the Save button.
    // While a reconfigure transaction is in flight (HostProfileSaveState.Saving),
    // the screen disables Save + shows a spinner. The VM's single-flight
    // already ignores double-submit; this is the UX side — the user gets
    // feedback that the save is running.
    isSaving: Boolean = false,
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
    // §tofu R2: the legacy `allowInsecure` toggle (per-host trust-all) is
    // GONE — self-signed / unknown-issuer endpoints now surface a TOFU trust
    // dialog at first connect (the connection coordinator captures the leaf
    // cert and asks the user to Accept once / Trust / Cancel). No editor
    // state needed.
    // §mtls-clipboard: 折叠区开关——新 profile 全 false（§design E），既有 profile
    // 按是否配置了对应凭据种子。三个区（Basic Auth / 隧道 / mTLS）共用
    // [CollapsibleSection] 容器，关则隐藏内容并在保存时清空对应凭据。
    var basicAuthEnabled by remember(initial.id) { mutableStateOf(initial.basicAuth != null) }
    var tunnelEnabled by remember(initial.id) { mutableStateOf(initial.tunnelPasswordId != null) }
    var mtlsEnabled by remember(initial.id) { mutableStateOf(initial.mtlsEnabled) }
    // §R8 slim-mode UI: 省流模式开关——与 mTLS 正交，形成四配置组合。
    var slimEnabled by remember(initial.id) { mutableStateOf(initial.slim) }
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
        // §tofu R2: allowInsecure 不再透传——TOFU 取代 trust-all 降级。
        onTestConnection(
            testResult.url,
            testResult.userSnap,
            testResult.authPwSnap,
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

    // §WT5: editor dialog consolidated onto the shared `AppFormDialog` primitive
    // (BasicAlertDialog + Surface + AlertDialogDefaults + verticalScroll +
    // heightIn(max=screen*0.85f)). Previously this was an `AlertDialog` with a
    // hand-rolled `text={ Column{ verticalScroll + heightIn(max=560.dp) {...} } }`
    // — AppFormDialog now provides the scrollable container once. Per
    // `AppFormDialog.kt` file header, the BasicAlertDialog route (not AlertDialog)
    // is mandatory when the body holds interactive controls — but this editor's
    // body is all OutlinedTextField / CollapsibleSection / Switch which AlertDialog
    // handles fine; the migration is for visual / structural consistency with the
    // rest of the settings dialogs, and to retire the bespoke heightIn(560.dp).
    //
    // Field logic is preserved verbatim — only the outer dialog shell changes.
    // The complex action Row (Test IconButton + Delete IconButton + Cancel +
    // Save Button) is forwarded as a single `confirmButton` slot; AppFormDialog
    // wraps it in Row(Arrangement.End), but the inner Row is fillMaxWidth +
    // SpaceBetween so it consumes the full width and the layout is preserved.
    AppFormDialog(
        onDismissRequest = onDismiss,
        title = if (initial.name.isBlank()) stringResource(R.string.host_profile_add_title)
                else stringResource(R.string.host_profile_edit_title),
        confirmButton = {
            // Bottom action row: [Test][Delete] ... [Cancel] [Save].
            // §issue-5: 测试连接从表单全宽按钮迁来此处的 test icon（NetworkCheck；
            // 测试中换进度圈、URL 空或测试中禁用）。删除键改为 icon-only（去文字标签），
            // 保留 error 着色 + contentDescription 供无障碍。两 icon 组成左簇。
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
                                modifier = Modifier.size(Dimens.iconSm),
                                // §stroke: 2dp indicator stroke (no Dimens token).
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
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacing2)) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    // §item7 (glm#4): Save 同时受 isTesting 门控——防测试进行中点 Save
                    //   取消在飞的测试协程。
                    // §review-2: 另受 hasCertError 门控——任一证书槽处于 Error 时
                    //   暂存意图已过期，禁止提交。
                    // §review-r4 (gpter R4 #1): 另受 mtlsEnabled && !hasMaterial 门控——
                    //   mTLS 开启但无可用客户端证书材料时禁用 Save，使原本会抛「需先导入
                    //   客户端证书」（错误回显在底层屏幕、被对话框遮挡，看起来像「点了没
                    //   反应」）的保存根本无法发起。
                    // §C-D3 rev-3 round-7 (review I5-R7): 另受 isSaving 门控——
                    //   a reconfigure transaction in flight 时禁用 Save 防 double-submit。
                    Button(
                        enabled = !isConverting && !isTesting && !hasCertError && !(mtlsEnabled && !hasMaterial) && !isSaving,
                        onClick = {
                            if (isConverting || isTesting || hasCertError || (mtlsEnabled && !hasMaterial) || isSaving) return@Button
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
                                slimEnabled = slimEnabled,
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
                                saveResult.slimOn,
                                saveResult.stagedP12,
                                saveResult.caStage,
                                saveResult.p12Password,
                                saveResult.p12PasswordEdited,
                                saveResult.hasMaterial,
                            )
                        }
                    ) {
                        // §C-D3 rev-3 round-7: show a spinner while a save transaction
                        // is in flight (matches the isConverting pattern above).
                        if (isConverting || isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(Dimens.iconSm),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(R.string.settings_save))
                        }
                    }
                }
            }
        },
        content = {
            // §WT5: form content emitted directly into AppFormDialog's Column Scope.
            // The previous AlertDialog.text Column wrapper (heightIn(max=560.dp) +
            // verticalScroll) is retired — AppFormDialog provides the scrollable
            // container with a screenHeight*0.85f cap.
            //
            // 配置名 (required) — label 槽替代上方独立 Text（M3 idiom + 省 1 行，§issue-6）
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.host_profile_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(Dimens.spacingCompact))
        // 服务器地址 (required) — label 槽（§issue-6）
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text(stringResource(R.string.settings_server_url)) },
            placeholder = { Text(stringResource(R.string.host_profile_url_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(Dimens.spacing3))
        // §profile-cleanup R1: hide Tunnel password behind an Advanced expander.
        // Default expanded when an existing tunnel is configured so the
        // credential stays discoverable; collapsed for new profiles.
        var advancedExpanded by remember(initial.id) {
            mutableStateOf(
                initial.tunnelPasswordId != null ||
                    initial.basicAuth != null ||
                    initial.mtlsEnabled ||
                    initial.slim ||
                    initial.serverGroupFp in listOf("A", "B", "C", "D")
            )
        }
        val advancedExpandedDesc = stringResource(R.string.common_collapse)
        val advancedCollapsedDesc = stringResource(R.string.common_expand)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.touchTargetMin)
                .clickable(role = Role.Button) { advancedExpanded = !advancedExpanded }
                .semantics(mergeDescendants = true) {
                    stateDescription = if (advancedExpanded) advancedExpandedDesc else advancedCollapsedDesc
                }
                .padding(vertical = Dimens.spacing1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stringResource(R.string.host_advanced),
                style = MaterialTheme.typography.titleSmall
            )
            Icon(
                imageVector = if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null
            )
        }
        AnimatedVisibility(
            visible = advancedExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            // §fix-advanced-overlap: AnimatedVisibility provides BoxScope, so all
            // direct children overlap at (0,0). Wrap in a Column to stack vertically.
            Column {
                CollapsibleSection(
                title = stringResource(R.string.host_section_basic_auth_title),
                subtitle = stringResource(R.string.host_section_basic_auth_sub),
                checked = basicAuthEnabled,
                onCheckedChange = { basicAuthEnabled = it },
            ) {
                OutlinedTextField(
                    value = authUsername,
                    onValueChange = { authUsername = it },
                    label = { Text(stringResource(R.string.host_profile_basic_auth_username)) },
                    placeholder = { Text(stringResource(R.string.common_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(Dimens.spacingCompact))
                OutlinedTextField(
                    value = authPassword,
                    onValueChange = {
                        passwordEdited = true
                        authPassword = it
                    },
                    label = { Text(stringResource(R.string.host_profile_basic_auth_password)) },
                    placeholder = {
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
            Spacer(modifier = Modifier.height(Dimens.spacing2))
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
            Spacer(modifier = Modifier.height(Dimens.spacing3))
            var groupExpanded by remember(initial.id) { mutableStateOf(false) }
            val groupOptions = listOf<Pair<String?, String>>(
                null to stringResource(R.string.host_group_none),
                "A" to "A", "B" to "B", "C" to "C", "D" to "D"
            )
            val selectedGroupLabel = groupOptions.find { it.first == selectedGroup }?.second
                ?: stringResource(R.string.host_group_none)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.host_group_label), style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { showGroupInfo = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.host_group_info), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.width(Dimens.spacing2))
                ExposedDropdownMenuBox(
                    expanded = groupExpanded,
                    onExpandedChange = { groupExpanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = selectedGroupLabel, onValueChange = {}, readOnly = true, singleLine = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = groupExpanded) }
                    )
                    ExposedDropdownMenu(expanded = groupExpanded, onDismissRequest = { groupExpanded = false }) {
                        groupOptions.forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                selectedGroup = value
                                groupExpanded = false
                            })
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(Dimens.spacing3))
            CollapsibleSection(
                title = stringResource(R.string.host_mtls_title),
                subtitle = stringResource(R.string.host_mtls_summary),
                checked = mtlsEnabled,
                onCheckedChange = { mtlsEnabled = it },
            ) {
                val mtlsBanner = mtlsErrorHint ?: mtlsImportError
                if (mtlsBanner != null) {
                    Text(mtlsBanner, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = Dimens.spacingCompact))
                }
                if (mtlsEnabled && !hasMaterial) {
                    Text(stringResource(R.string.host_mtls_missing_client_cert), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = Dimens.spacingCompact))
                }
                CompactCertStatusRow(
                    clientStatus = clientSlotStatus, caStatus = caSlotStatus,
                    clientLabel = stringResource(R.string.host_cert_compact_client), caLabel = stringResource(R.string.host_cert_compact_ca),
                    clientPasteLabel = pasteClientLabel, caPasteLabel = pasteCaLabel,
                    onImportClient = { triggerClientPaste() }, onImportCa = { triggerCaPaste() },
                    onClearAll = {
                        clientEdited = true; caEdited = true; clientCleared = true; mtlsEnabled = false
                        stagedP12 = null; clientSlotStatus = CertSlotStatus.Empty; caStage = CaStage.Clear
                        caSlotStatus = CertSlotStatus.Empty; mtlsImportError = null
                    },
                    enabled = !isConverting,
                )
            }
            Spacer(modifier = Modifier.height(Dimens.spacing2))
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.touchTargetMin).padding(vertical = Dimens.spacing1),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.host_slim_title), style = MaterialTheme.typography.titleSmall)
                Switch(checked = slimEnabled, onCheckedChange = { slimEnabled = it })
            }
            } // Column (fix-advanced-overlap)
        }
        // §issue-5: 全宽"测试连接"按钮已移除——触发器移入底部 action 行的
        // test icon（见 confirmButton）。此处仅保留结果回显（成功/失败小字），
        // 测试进行中由 icon 内的进度圈表达。
        testStatus?.let { (success, msg) ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = Dimens.spacing1)
            )
        }
        // §fix-401: 旧提示"编辑已有配置时请重新输入密码后再测试"已移除——
        // 现在 VM 会在未编辑密码时自动回退已保存密码，无需用户重输。
        }
    )

    if (showDeleteConfirm) {
        // §WT5: host-profile delete confirm consolidated onto AppConfirmDialog
        // (was a hand-rolled AlertDialog with error-tinted confirm TextButton).
        // Callbacks + messages preserved verbatim; destructive=true restores
        // the error-colored confirm button.
        AppConfirmDialog(
            title = stringResource(R.string.host_profile_delete_confirm_title),
            body = stringResource(R.string.host_profile_delete_confirm_message),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showDeleteConfirm = false },
            destructive = true,
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

    // §R8 slim-mode M2 自检：版本不兼容阻塞对话框（Tier C — AlertDialog family）。
    // fail-closed：当 ServerCompatProfile.isSlimapiClientAccepted() == false 时，
    // slimapiVersionIncompatible 被设为非 null，UI 必须阻塞并提示用户升级。
    // §reconcile: 此值由调用方 HostProfilesManagerScreen 从
    // connectionState.slimapiVersionIncompatible 注入（参数），dialog 本身不持有
    // ConnectionViewModel 引用，避免 Unresolved reference 编译错误 + 保持纯 UI 可测。
    val versionIncompat = slimapiVersionIncompatible
    if (versionIncompat != null) {
        val (clientVer, minVer, maxVer) = versionIncompat
        AppConfirmDialog(
            title = stringResource(R.string.slimapi_version_incompatible_title),
            body = stringResource(
                R.string.slimapi_version_incompatible_message,
                clientVer, minVer, maxVer
            ),
            confirmText = stringResource(R.string.slimapi_version_incompatible_dismiss),
            onConfirm = { /* dismiss only — user must upgrade externally */ },
            dismissText = stringResource(R.string.slimapi_version_incompatible_dismiss),
            onDismiss = { /* dismiss only */ },
            destructive = false,
        )
    }
}

/**
 * §profile-cleanup R1: compact two-slot mTLS certificate status row.
 * Shows icon-only status for the client and CA certificates and a single
 * trash affordance that clears both and disables mTLS.
 */
@Composable
private fun CompactCertStatusRow(
    clientStatus: CertSlotStatus,
    caStatus: CertSlotStatus,
    clientLabel: String,
    caLabel: String,
    clientPasteLabel: String,
    caPasteLabel: String,
    onImportClient: () -> Unit,
    onImportCa: () -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val hasAnythingToClear =
        clientStatus is CertSlotStatus.Imported || caStatus is CertSlotStatus.Imported
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CompactCertIndicator(
            label = clientLabel,
            pasteLabel = clientPasteLabel,
            status = clientStatus,
            onImport = onImportClient,
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
        CompactCertIndicator(
            label = caLabel,
            pasteLabel = caPasteLabel,
            status = caStatus,
            onImport = onImportCa,
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onClearAll,
            enabled = enabled && hasAnythingToClear
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.common_delete),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun CompactCertIndicator(
    label: String,
    pasteLabel: String,
    status: CertSlotStatus,
    onImport: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val importedDescription = stringResource(R.string.host_cert_status_imported, label)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (status) {
            is CertSlotStatus.Imported -> {
                Icon(
                    Icons.Default.Check,
                    contentDescription = importedDescription,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            is CertSlotStatus.Error -> {
                IconButton(onClick = onImport, enabled = enabled) {
                    Icon(
                        Icons.Default.ContentPaste,
                        contentDescription = pasteLabel,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    status.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            CertSlotStatus.Empty -> {
                IconButton(onClick = onImport, enabled = enabled) {
                    Icon(
                        Icons.Default.ContentPaste,
                        contentDescription = pasteLabel,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

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
