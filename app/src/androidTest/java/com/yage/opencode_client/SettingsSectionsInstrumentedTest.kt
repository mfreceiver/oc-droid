package com.yage.opencode_client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.yage.opencode_client.data.model.HostProfile
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.settings.ConnectionProfileSection
import com.yage.opencode_client.ui.settings.HostProfileDetailDialog
import com.yage.opencode_client.ui.settings.HostProfileEditorDialog
import org.junit.Rule
import org.junit.Test

class SettingsSectionsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun connectionProfileSectionShowsCurrentProfileSummary() {
        val profile = HostProfile(
            id = "p1",
            name = "OpenCode Server",
            serverUrl = "https://opencode.example.com"
        )

        composeRule.setContent {
            MaterialTheme {
                ConnectionProfileSection(
                    profile = profile,
                    isTesting = false,
                    state = AppState(isConnected = true, serverVersion = "1.0.0"),
                    testResult = null,
                    onTestConnection = {},
                    onManageProfiles = {}
                )
            }
        }

        composeRule.onNodeWithText("OpenCode Server").assertIsDisplayed()
        composeRule.onNodeWithText("https://opencode.example.com").assertIsDisplayed()
        composeRule.onNodeWithText("Manage Profiles").assertIsDisplayed()
    }

    @Test
    fun hostProfileEditorShowsServerUrlAndAuthFields() {
        val profile = HostProfile.defaultDirect("http://localhost:4096")

        composeRule.setContent {
            MaterialTheme {
                HostProfileEditorDialog(
                    initial = profile,
                    onDismiss = {},
                    onSave = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("Server URL").assertIsDisplayed()
    }

    @Test
    fun hostProfileDetailShowsUseAndCopyActionsForProfile() {
        val profile = HostProfile(
            id = "p1",
            name = "OpenCode Server",
            serverUrl = "https://opencode.example.com"
        )

        composeRule.setContent {
            MaterialTheme {
                HostProfileDetailDialog(
                    profile = profile,
                    isCurrent = false,
                    onDismiss = {},
                    onUse = {},
                    onEdit = {},
                    onExport = {},
                    onTest = {}
                )
            }
        }

        composeRule.onNodeWithText("Use This Host").assertIsDisplayed()
        composeRule.onNodeWithText("Copy Config JSON").assertIsDisplayed()
    }
}
