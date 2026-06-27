package com.yage.opencode_client.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yage.opencode_client.R
import com.yage.opencode_client.data.model.BasicAuthConfig
import com.yage.opencode_client.data.model.HostProfile
import com.yage.opencode_client.ui.MainViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: (() -> Unit)? = null
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var showHostProfiles by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }

    LaunchedEffect(state.isConnecting) {
        if (!state.isConnecting && isTesting) {
            isTesting = false
            testResult = TestResult(
                success = state.isConnected,
                message = if (state.isConnected) {
                    "Connected successfully" + (state.serverVersion?.let { " (v$it)" } ?: "")
                } else {
                    state.error ?: "Connection failed"
                }
            )
        }
    }

    // Auto-dismiss the transient "Settings saved" notice after a short delay.
    // Connection results (success/error) stay until the user changes a field.
    LaunchedEffect(testResult) {
        if (testResult?.message == "Settings saved") {
            delay(2000)
            testResult = null
        }
    }

    if (showHostProfiles) {
        HostProfilesManagerScreen(
            viewModel = viewModel,
            profiles = state.hostProfiles,
            currentProfileId = state.currentHostProfileId,
            onBack = { showHostProfiles = false }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (onBack != null) {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            ConnectionProfileSection(
                profile = state.hostProfiles.firstOrNull { it.id == state.currentHostProfileId } ?: viewModel.currentHostProfile(),
                isTesting = isTesting,
                state = state,
                testResult = testResult,
                onTestConnection = {
                    isTesting = true
                    testResult = null
                    viewModel.testConnection(force = true)
                },
                onManageProfiles = { showHostProfiles = true }
            )

            SettingsSectionDivider()

            AppearanceSection(
                themeMode = state.themeMode,
                languageMode = state.languageMode,
                onThemeSelected = viewModel::setThemeMode,
                onLanguageSelected = viewModel::setLanguageMode
            )

            SettingsSectionDivider()

            AboutSection()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostProfilesManagerScreen(
    viewModel: MainViewModel,
    profiles: List<HostProfile>,
    currentProfileId: String?,
    onBack: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var editingProfile by remember { mutableStateOf<HostProfile?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var exportText by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var detailProfile by remember { mutableStateOf<HostProfile?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.host_profiles_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            },
            actions = {
                IconButton(onClick = { importing = true }) {
                    Icon(Icons.Default.FileDownload, contentDescription = stringResource(R.string.host_profile_import_json))
                }
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
                    canDelete = profiles.size > 1,
                    onOpen = { detailProfile = profile },
                    onEdit = { editingProfile = profile },
                    onDuplicate = { viewModel.duplicateHostProfile(profile.id) },
                    onExport = { exportText = viewModel.exportHostProfile(profile) },
                    onDelete = {
                        runCatching { viewModel.deleteHostProfile(profile.id) }
                            .onFailure { error = it.message }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    editingProfile?.let { profile ->
        HostProfileEditorDialog(
            initial = profile,
            onDismiss = { editingProfile = null },
            onSave = { saved, password, tunnelPassword ->
                viewModel.saveHostProfile(saved, password, tunnelPassword)
                editingProfile = null
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
            },
            onExport = { exportText = viewModel.exportHostProfile(profile) },
            onTest = {
                viewModel.selectHostProfile(profile.id)
                detailProfile = null
            }
        )
    }

    if (importing) {
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text(stringResource(R.string.host_profile_import_json)) },
            text = {
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    label = { Text(stringResource(R.string.common_json)) },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.importHostProfile(importText)
                        .onFailure { error = it.message ?: "Import failed" }
                    importing = false
                    importText = ""
                }) { Text(stringResource(R.string.common_import)) }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    exportText?.let { json ->
        AlertDialog(
            onDismissRequest = { exportText = null },
            title = { Text(stringResource(R.string.host_profile_export_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.host_profile_no_secrets), style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(json, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    clipboard.setText(AnnotatedString(json))
                    exportText = null
                }) { Text(stringResource(R.string.host_profile_copy_json)) }
            },
            dismissButton = { TextButton(onClick = { exportText = null }) { Text(stringResource(R.string.common_close)) } }
        )
    }
}

@Composable
internal fun HostProfileRow(
    profile: HostProfile,
    selected: Boolean,
    canDelete: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("host.profile.row.${profile.id}")
    ) {
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(if (selected) stringResource(R.string.host_profile_current_suffix, profile.displayName) else profile.displayName)
            Text(profile.connectionSummary, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = { menuExpanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.host_profile_actions))
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) }, onClick = { menuExpanded = false; onEdit() }, leadingIcon = { Icon(Icons.Default.Edit, null) })
            DropdownMenuItem(text = { Text(stringResource(R.string.common_duplicate)) }, onClick = { menuExpanded = false; onDuplicate() })
            DropdownMenuItem(text = { Text(stringResource(R.string.host_profile_export_json)) }, onClick = { menuExpanded = false; onExport() }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) })
            DropdownMenuItem(
                text = { Text(if (canDelete) stringResource(R.string.common_delete) else stringResource(R.string.host_profile_keep_one)) },
                onClick = { menuExpanded = false; if (canDelete) onDelete() },
                enabled = canDelete,
                leadingIcon = { Icon(Icons.Default.Delete, null) }
            )
        }
    }
}

@Composable
internal fun HostProfileDetailDialog(
    profile: HostProfile,
    isCurrent: Boolean,
    onDismiss: () -> Unit,
    onUse: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onTest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(profile.displayName) },
        text = {
            Column {
                Text(stringResource(R.string.host_profile_opencode_url, profile.serverUrl))
                Text(stringResource(R.string.host_profile_status, if (isCurrent) stringResource(R.string.host_profile_current) else stringResource(R.string.host_profile_saved)))
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (!isCurrent) {
                    Button(onClick = onUse) { Text(stringResource(R.string.host_profile_use_this_host)) }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onTest) { Text(stringResource(R.string.settings_test_connection)) }
                    OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.common_edit)) }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onExport) { Text(stringResource(R.string.host_profile_copy_config_json)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } }
    )
}

@Composable
internal fun HostProfileEditorDialog(
    initial: HostProfile,
    onDismiss: () -> Unit,
    onSave: (HostProfile, String?, String?) -> Unit,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var serverUrl by remember(initial.id) { mutableStateOf(initial.serverUrl) }
    var authUsername by remember(initial.id) { mutableStateOf(initial.basicAuth?.username.orEmpty()) }
    var authPassword by remember(initial.id) { mutableStateOf("") }
    var tunnelPassword by remember(initial.id) { mutableStateOf("") }
    var showTunnelPassword by remember(initial.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) stringResource(R.string.host_profile_add_title) else stringResource(R.string.host_profile_edit_title)) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.host_profile_name)) }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = serverUrl, onValueChange = { serverUrl = it }, label = { Text(stringResource(R.string.settings_server_url)) }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = authUsername, onValueChange = { authUsername = it }, label = { Text(stringResource(R.string.host_profile_basic_auth_username)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = authPassword, onValueChange = { authPassword = it }, label = { Text(stringResource(R.string.host_profile_basic_auth_password)) }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = tunnelPassword,
                    onValueChange = { tunnelPassword = it },
                    label = { Text(stringResource(R.string.host_profile_tunnel_password_label)) },
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
                    },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) }
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val basicAuth = authUsername.ifBlank { null }?.let { BasicAuthConfig(username = it, passwordId = initial.id) }
                val tunnelId = tunnelPassword.ifBlank { null }?.let { initial.id }
                val saved = initial.copy(
                    name = name.ifBlank { "Untitled" },
                    serverUrl = serverUrl,
                    basicAuth = basicAuth,
                    tunnelPasswordId = tunnelId
                )
                onSave(saved, authPassword.ifBlank { null }, tunnelPassword.ifBlank { null })
            }) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

private fun newDirectProfile(): HostProfile = HostProfile.defaultDirect()
