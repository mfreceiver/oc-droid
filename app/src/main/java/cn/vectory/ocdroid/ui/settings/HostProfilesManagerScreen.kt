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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import cn.vectory.ocdroid.ui.MainViewModel

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
    viewModel: MainViewModel,
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
        HostProfileEditorDialog(
            initial = profile,
            // The "+" action creates a fresh profile that isn't persisted yet,
            // so it must not expose the destructive delete affordance.
            canDelete = profiles.any { it.id == profile.id } && profiles.size > 1,
            onDismiss = { editingProfile = null },
            onSave = { saved, basicAuthPassword, basicAuthEdited, tunnelPassword, tunnelEdited ->
                viewModel.saveHostProfile(
                    saved,
                    basicAuthPassword = basicAuthPassword,
                    basicAuthEdited = basicAuthEdited,
                    tunnelPassword = tunnelPassword,
                    tunnelEdited = tunnelEdited
                )
                editingProfile = null
            },
            onDelete = {
                runCatching { viewModel.deleteHostProfile(profile.id) }
                    .onFailure { error = it.message ?: deleteFailedText }
                editingProfile = null
            },
            // §user-req: 表单"测试连接"按钮直连 MainViewModel.testConnectionForm。
            // 注意：表单里若未编辑密码（passwordEdited=false），authPassword
            // 是空串——testConnectionForm 会拿空密码探测；这是预期行为，因为
            // 已保存的密码不会回填到表单（write-only）。用户若要带 Basic Auth
            // 测试，需在点测试前在密码框里重新输入。
            onTestConnection = { url, user, pass, insecure, callback ->
                viewModel.testConnectionForm(url, user, pass, insecure, callback)
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
        tunnelEdited: Boolean
    ) -> Unit,
    canDelete: Boolean = false,
    onDelete: () -> Unit = {},
    // §user-req: 一次性"测试连接"回调。调用方（HostProfilesManagerScreen）
    // 把 MainViewModel.testConnectionForm 注入进来；Dialog 不持有 ViewModel
    // 引用，保持纯 UI 组件可测试性。默认 no-op 以兼容不关心此能力的调用方
    // （如 SettingsSectionsInstrumentedTest）。
    onTestConnection: (
        baseUrl: String,
        username: String?,
        password: String?,
        allowInsecure: Boolean,
        onResult: (Boolean, String) -> Unit
    ) -> Unit = { _, _, _, _, _ -> }
) {
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
    // R-01: per-host "接受不安全连接"开关（自签证书/内网 TLS 用户需要显式启用，
    // 否则全局 strict 校验会直接拒绝连接）。种子取自当前 HostProfile。
    var allowInsecure by remember(initial.id) { mutableStateOf(initial.allowInsecureConnections) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) stringResource(R.string.host_profile_add_title) else stringResource(R.string.host_profile_edit_title)) },
        text = {
            Column {
                // 配置名 (required)
                Text(stringResource(R.string.host_profile_name), style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(stringResource(R.string.host_profile_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 服务器地址 (required)
                Text(stringResource(R.string.settings_server_url), style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    placeholder = { Text("http://localhost:4096") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Username (optional)
                Text(stringResource(R.string.host_profile_basic_auth_username), style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = authUsername,
                    onValueChange = { authUsername = it },
                    placeholder = { Text("（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Password (optional, masked)
                Text(stringResource(R.string.host_profile_basic_auth_password), style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = authPassword,
                    onValueChange = {
                        passwordEdited = true
                        authPassword = it
                    },
                    placeholder = {
                        // Mirror the tunnel-password field: the password is
                        // write-only (never echoed back), but show masked dots
                        // when a password is already stored so reopening the
                        // editor doesn't look like the credential vanished.
                        Text(
                            if (initial.basicAuth != null && !passwordEdited) "••••••••"
                            else "（可选）"
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
                Spacer(modifier = Modifier.height(8.dp))
                // Tunnel auth (optional, masked)
                Text(stringResource(R.string.host_profile_tunnel_password_label), style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = tunnelPassword,
                    onValueChange = {
                        tunnelEdited = true
                        tunnelPassword = it
                    },
                    placeholder = {
                        // When a tunnel password is already stored for this host
                        // (and the user hasn't started editing), show masked dots
                        // so reopening the editor doesn't look like the credential
                        // vanished. The field stays write-only (the actual password
                        // is never echoed back), but the dots signal "data present".
                        Text(
                            if (initial.tunnelPasswordId != null && !tunnelEdited) "••••••••"
                            else "（可选）"
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
                // R-01: per-host insecure-connections toggle. Off by default
                // (strict TLS); enabling it downgrades this host's REST/SSE/
                // health/tunnel clients to trust-all so self-signed or internal
                // TLS servers can connect. The warning makes the MITM risk
                // explicit at the point of enablement.
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
                    Switch(checked = allowInsecure, onCheckedChange = { allowInsecure = it })
                }
                Spacer(modifier = Modifier.height(12.dp))
                // §user-req: 一次性"测试连接"按钮。用 onTestConnection 回调
                // 探测当前表单值（不保存、不切换 host）。结果以彩色小字回显在
                // 按钮下方；测试进行中按钮禁用并显示进度圈。
                var testStatus by remember(initial.id) { mutableStateOf<Pair<Boolean, String>?>(null) }
                var isTesting by remember(initial.id) { mutableStateOf(false) }
                OutlinedButton(
                    onClick = {
                        isTesting = true
                        testStatus = null
                        onTestConnection(
                            serverUrl,
                            authUsername.ifBlank { null },
                            authPassword.ifBlank { null },
                            allowInsecure
                        ) { success, msg ->
                            isTesting = false
                            testStatus = success to msg
                        }
                    },
                    enabled = !isTesting && serverUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("测试连接")
                }
                testStatus?.let { (success, msg) ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Text(
                    text = "编辑已有配置时请重新输入密码后再测试",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        },
        // Bottom action row: [Delete(red)] ... [Cancel] [Save].
        // Delete is only shown when editing an existing, deletable profile and
        // is tinted with the error color to make the destructive action stand
        // out. The confirmButton Row carries all three actions so they share a
        // single baseline and the delete button can sit on the far left.
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (canDelete) {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.common_delete))
                    }
                } else {
                    Spacer(Modifier)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    Button(onClick = {
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
                            allowInsecureConnections = allowInsecure
                        )
                        // If the user blanks the username on a profile that
                        // previously had basic auth, force passwordEdited so
                        // saveHostProfile removes any leftover stored password.
                        val effectivePasswordEdited = passwordEdited ||
                            (authUsername.isBlank() && initial.basicAuth != null)
                        // Positional call: onSave is a function-type parameter,
                        // so Kotlin prohibits named arguments here. Names are
                        // documented by the lambda signature instead.
                        onSave(
                            saved,
                            authPassword,
                            effectivePasswordEdited,
                            tunnelPassword,
                            tunnelEdited
                        )
                    }) { Text(stringResource(R.string.settings_save)) }
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
}

private fun newDirectProfile(): HostProfile = HostProfile.defaultDirect()
