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
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yage.opencode_client.data.model.BasicAuthConfig
import com.yage.opencode_client.data.model.HostProfile
import com.yage.opencode_client.data.model.HostTransport
import com.yage.opencode_client.data.model.SshTunnelConfig
import com.yage.opencode_client.ui.MainViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: (() -> Unit)? = null
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val savedAIBuilder = remember(viewModel) { viewModel.getAIBuilderSettings() }

    var showHostProfiles by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    var aiBuilderBaseURL by remember { mutableStateOf(savedAIBuilder.baseURL) }
    var aiBuilderToken by remember { mutableStateOf(savedAIBuilder.token) }
    var aiBuilderCustomPrompt by remember { mutableStateOf(savedAIBuilder.customPrompt) }
    var aiBuilderTerminology by remember { mutableStateOf(savedAIBuilder.terminology) }
    var showAIBuilderToken by remember { mutableStateOf(false) }
    // Independent "Settings saved" notice for the Speech section, so it shows in
    // its own section rather than reusing the server section's testResult.
    var aiBuilderSaveMessage by remember { mutableStateOf<String?>(null) }

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

    // Auto-dismiss the transient "Settings saved" notices after a short delay,
    // for both the server and speech sections. Connection results (success/error)
    // stay until the user changes a field.
    LaunchedEffect(testResult) {
        if (testResult?.message == "Settings saved") {
            delay(2000)
            testResult = null
        }
    }
    LaunchedEffect(aiBuilderSaveMessage) {
        if (aiBuilderSaveMessage != null) {
            delay(2000)
            aiBuilderSaveMessage = null
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
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                onThemeSelected = viewModel::setThemeMode
            )

            SettingsSectionDivider()

            SpeechRecognitionSection(
                state = state,
                aiBuilderBaseURL = aiBuilderBaseURL,
                aiBuilderToken = aiBuilderToken,
                aiBuilderCustomPrompt = aiBuilderCustomPrompt,
                aiBuilderTerminology = aiBuilderTerminology,
                showAIBuilderToken = showAIBuilderToken,
                saveMessage = aiBuilderSaveMessage,
                onBaseUrlChange = {
                    aiBuilderBaseURL = it
                    aiBuilderSaveMessage = null
                },
                onTokenChange = {
                    aiBuilderToken = it
                    aiBuilderSaveMessage = null
                },
                onPromptChange = {
                    aiBuilderCustomPrompt = it
                    aiBuilderSaveMessage = null
                },
                onTerminologyChange = {
                    aiBuilderTerminology = it
                    aiBuilderSaveMessage = null
                },
                onToggleTokenVisibility = { showAIBuilderToken = !showAIBuilderToken },
                onTestConnection = {
                    aiBuilderSaveMessage = null
                    viewModel.saveAIBuilderSettings(
                        buildAIBuilderSettings(
                            baseURL = aiBuilderBaseURL,
                            token = aiBuilderToken,
                            customPrompt = aiBuilderCustomPrompt,
                            terminology = aiBuilderTerminology
                        )
                    )
                    viewModel.testAIBuilderConnection()
                },
                onSave = {
                    viewModel.saveAIBuilderSettings(
                        buildAIBuilderSettings(
                            baseURL = aiBuilderBaseURL,
                            token = aiBuilderToken,
                            customPrompt = aiBuilderCustomPrompt,
                            terminology = aiBuilderTerminology
                        )
                    )
                    aiBuilderSaveMessage = "Settings saved"
                }
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
    var sshPublicKey by remember { mutableStateOf(viewModel.sshPublicKey()) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Host Profiles") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { importing = true }) {
                    Icon(Icons.Default.FileUpload, contentDescription = "Import host profile JSON")
                }
                IconButton(onClick = { editingProfile = newDirectProfile() }) {
                    Icon(Icons.Default.Add, contentDescription = "Add host profile")
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
                    onSelect = { viewModel.selectHostProfile(profile.id) },
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
            publicKey = sshPublicKey,
            onDismiss = { editingProfile = null },
            onSave = { saved, password ->
                viewModel.saveHostProfile(saved, password)
                editingProfile = null
            },
            onCopyPublicKey = {
                val key = viewModel.ensureSshPublicKey()
                sshPublicKey = key
                clipboard.setText(AnnotatedString(key))
            },
            onRotateKey = {
                val key = viewModel.rotateSshKey()
                sshPublicKey = key
                clipboard.setText(AnnotatedString(key))
            }
        )
    }

    if (importing) {
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text("Import Host Profile JSON") },
            text = {
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    label = { Text("JSON") },
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
                }) { Text("Import Profile") }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text("Cancel") } }
        )
    }

    exportText?.let { json ->
        AlertDialog(
            onDismissRequest = { exportText = null },
            title = { Text("Export Host Profile JSON") },
            text = {
                Column {
                    Text("Secrets are not included.", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(json, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    clipboard.setText(AnnotatedString(json))
                    exportText = null
                }) { Text("Copy JSON") }
            },
            dismissButton = { TextButton(onClick = { exportText = null }) { Text("Close") } }
        )
    }
}

@Composable
internal fun HostProfileRow(
    profile: HostProfile,
    selected: Boolean,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("host.profile.row.${profile.id}")
    ) {
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(if (selected) "${profile.displayName} · Current" else profile.displayName)
            Text(profile.connectionSummary, style = MaterialTheme.typography.bodySmall)
        }
        Text(if (profile.transport == HostTransport.SSH_TUNNEL) "SSH Tunnel" else "Direct")
        IconButton(onClick = { menuExpanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Host profile actions")
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(text = { Text("Edit") }, onClick = { menuExpanded = false; onEdit() }, leadingIcon = { Icon(Icons.Default.Edit, null) })
            DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menuExpanded = false; onDuplicate() })
            DropdownMenuItem(text = { Text("Export JSON") }, onClick = { menuExpanded = false; onExport() }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) })
            DropdownMenuItem(
                text = { Text(if (canDelete) "Delete" else "Keep at least one profile") },
                onClick = { menuExpanded = false; if (canDelete) onDelete() },
                enabled = canDelete,
                leadingIcon = { Icon(Icons.Default.Delete, null) }
            )
        }
    }
}

@Composable
internal fun HostProfileEditorDialog(
    initial: HostProfile,
    publicKey: String?,
    onDismiss: () -> Unit,
    onSave: (HostProfile, String?) -> Unit,
    onCopyPublicKey: () -> Unit,
    onRotateKey: () -> Unit
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var transport by remember(initial.id) { mutableStateOf(initial.transport) }
    var serverUrl by remember(initial.id) { mutableStateOf(initial.serverUrl) }
    var authUsername by remember(initial.id) { mutableStateOf(initial.basicAuth?.username.orEmpty()) }
    var authPassword by remember(initial.id) { mutableStateOf("") }
    var sshHost by remember(initial.id) { mutableStateOf(initial.ssh?.host.orEmpty()) }
    var sshPort by remember(initial.id) { mutableStateOf((initial.ssh?.port ?: 8006).toString()) }
    var sshUsername by remember(initial.id) { mutableStateOf(initial.ssh?.username ?: "opencode") }
    var remotePort by remember(initial.id) { mutableStateOf((initial.ssh?.remotePort ?: 19001).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) "Add Host Profile" else "Edit Host Profile") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Profile name") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = transport == HostTransport.DIRECT,
                        onClick = { transport = HostTransport.DIRECT },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        modifier = Modifier.testTag("host.editor.transport.direct")
                    ) { Text("Direct") }
                    SegmentedButton(
                        selected = transport == HostTransport.SSH_TUNNEL,
                        onClick = { transport = HostTransport.SSH_TUNNEL },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        modifier = Modifier.testTag("host.editor.transport.ssh")
                    ) { Text("SSH Tunnel") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (transport == HostTransport.DIRECT) {
                    OutlinedTextField(value = serverUrl, onValueChange = { serverUrl = it }, label = { Text("Server URL") }, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(value = sshHost, onValueChange = { sshHost = it }, label = { Text("SSH gateway host") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = sshPort, onValueChange = { sshPort = it }, label = { Text("SSH port") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = sshUsername, onValueChange = { sshUsername = it }, label = { Text("SSH username") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = remotePort, onValueChange = { remotePort = it }, label = { Text("Assigned remote port") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Device public key", style = MaterialTheme.typography.labelMedium)
                    Text(
                        publicKey ?: "No device key generated yet. Copy public key to generate one.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("host.editor.ssh.publicKey")
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onCopyPublicKey) { Text("Copy public key") }
                        OutlinedButton(onClick = onRotateKey) { Text("Rotate key") }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = authUsername, onValueChange = { authUsername = it }, label = { Text("OpenCode Basic Auth username (optional)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = authPassword, onValueChange = { authPassword = it }, label = { Text("OpenCode Basic Auth password (optional)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = {
                val basicAuth = authUsername.ifBlank { null }?.let { BasicAuthConfig(username = it, passwordId = initial.id) }
                val saved = if (transport == HostTransport.DIRECT) {
                    initial.copy(name = name.ifBlank { "Untitled" }, transport = HostTransport.DIRECT, serverUrl = serverUrl, basicAuth = basicAuth, ssh = null)
                } else {
                    initial.copy(
                        name = name.ifBlank { "Untitled" },
                        transport = HostTransport.SSH_TUNNEL,
                        serverUrl = "http://127.0.0.1:4096",
                        basicAuth = basicAuth,
                        ssh = SshTunnelConfig(
                            host = sshHost,
                            port = sshPort.toIntOrNull() ?: 8006,
                            username = sshUsername,
                            remotePort = remotePort.toIntOrNull() ?: 19001
                        )
                    )
                }
                onSave(saved, authPassword.ifBlank { null })
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun newDirectProfile(): HostProfile = HostProfile.defaultDirect()
