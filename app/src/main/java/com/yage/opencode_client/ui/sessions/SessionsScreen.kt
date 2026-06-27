package com.yage.opencode_client.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.yage.opencode_client.R
import com.yage.opencode_client.Screen
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    viewModel: MainViewModel,
    navController: NavController?
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showNewWorkdirDialog by remember { mutableStateOf(false) }
    var newWorkdirPath by remember { mutableStateOf("") }
    var expandedWorkdirs by remember { mutableStateOf(setOf<String>()) }

    // Derive recent sessions (root sessions: parentId == null, by time.updated desc, top 5)
    val recentSessions = remember(state.sessions) {
        state.sessions
            .filter { it.parentId == null && !it.isArchived }
            .sortedByDescending { it.time?.updated ?: 0L }
            .take(5)
    }

    // Derive workdir groups
    val workdirGroups = remember(state.sessions) {
        state.sessions
            .groupBy { it.directory }
            .mapValues { (_, sessList) ->
                sessList.sortedByDescending { it.time?.updated ?: 0L }
            }
            .entries
            .sortedBy { it.key }
    }

    // Navigate to Chat tab after selecting a session
    fun onSessionClick(sessionId: String) {
        viewModel.selectSession(sessionId)
        navController?.navigate(Screen.Chat.route) {
            popUpTo(Screen.Chat.route) { inclusive = true }
            launchSingleTop = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.nav_sessions))
                },
                actions = {
                    IconButton(onClick = { showNewWorkdirDialog = true }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.sessions_tab_new_workdir)
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // --- Recent Sessions Section ---
            item(key = "recent_header") {
                SectionHeader(
                    icon = Icons.Default.History,
                    title = stringResource(R.string.sessions_tab_recent)
                )
            }

            if (recentSessions.isEmpty()) {
                item(key = "recent_empty") {
                    EmptyRow(stringResource(R.string.sessions_tab_no_sessions))
                }
            } else {
                items(recentSessions, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        onClick = { onSessionClick(session.id) }
                    )
                }
            }

            // --- Workdirs Section ---
            item(key = "workdirs_header") {
                SectionHeader(
                    icon = Icons.Default.Folder,
                    title = stringResource(R.string.sessions_tab_workdirs)
                )
            }

            if (workdirGroups.isEmpty()) {
                item(key = "workdirs_empty") {
                    EmptyRow(stringResource(R.string.sessions_tab_no_workdirs))
                }
            } else {
                items(workdirGroups, key = { "workdir_${it.key}" }) { (workdir, sessionsInWorkdir) ->
                    val isExpanded = expandedWorkdirs.contains(workdir)
                    val displayName = workdir.split("/").filter { it.isNotEmpty() }.lastOrNull()
                        ?: workdir

                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Workdir header row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedWorkdirs = if (isExpanded) {
                                        expandedWorkdirs - workdir
                                    } else {
                                        expandedWorkdirs + workdir
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isExpanded) Icons.Default.KeyboardArrowDown
                                else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = if (isExpanded) "Collapse" else "Expand",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = workdir,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            IconButton(
                                onClick = {
                                    viewModel.createSessionInWorkdir(workdir)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = stringResource(R.string.sessions_tab_create_session),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Expandable session list within workdir
                        AnimatedVisibility(visible = isExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 40.dp, end = 16.dp)
                            ) {
                                sessionsInWorkdir.forEach { session ->
                                    SessionCard(
                                        session = session,
                                        onClick = { onSessionClick(session.id) }
                                    )
                                }
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }

    // --- New Workdir Dialog ---
    if (showNewWorkdirDialog) {
        AlertDialog(
            onDismissRequest = {
                showNewWorkdirDialog = false
                newWorkdirPath = ""
            },
            title = { Text(stringResource(R.string.sessions_tab_new_workdir)) },
            text = {
                OutlinedTextField(
                    value = newWorkdirPath,
                    onValueChange = { newWorkdirPath = it },
                    label = { Text(stringResource(R.string.sessions_tab_workdir_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val path = newWorkdirPath.trim()
                        if (path.isNotEmpty()) {
                            viewModel.createSessionInWorkdir(path)
                        }
                        showNewWorkdirDialog = false
                        newWorkdirPath = ""
                    }
                ) {
                    Text(stringResource(R.string.sessions_tab_add_workdir))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNewWorkdirDialog = false
                        newWorkdirPath = ""
                    }
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SessionCard(session: Session, onClick: () -> Unit) {
    val dirBasename = session.directory.split("/").filter { it.isNotEmpty() }.lastOrNull()
        ?: session.directory
    val updatedText = session.time?.updated?.let { formatTime(it) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = session.displayName,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = dirBasename,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (updatedText != null) {
                    Text(
                        text = "  •  $updatedText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
    )
}

private fun formatTime(epochMs: Long): String {
    return try {
        val sdf = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        sdf.format(Date(epochMs))
    } catch (_: Exception) {
        ""
    }
}
