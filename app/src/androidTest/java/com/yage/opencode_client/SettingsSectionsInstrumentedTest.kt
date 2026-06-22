package com.yage.opencode_client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.yage.opencode_client.data.model.HostProfile
import com.yage.opencode_client.data.model.HostTransport
import com.yage.opencode_client.data.model.SshTunnelConfig
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.settings.ConnectionProfileSection
import com.yage.opencode_client.ui.settings.HostProfileEditorDialog
import com.yage.opencode_client.ui.settings.SpeechRecognitionSection
import org.junit.Rule
import org.junit.Test

class SettingsSectionsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun speechSectionDisablesActionsWhenBaseUrlIsBlank() {
        composeRule.setContent {
            MaterialTheme {
                SpeechRecognitionSection(
                    state = AppState(),
                    aiBuilderBaseURL = "",
                    aiBuilderToken = "",
                    aiBuilderCustomPrompt = "",
                    aiBuilderTerminology = "",
                    showAIBuilderToken = false,
                    onBaseUrlChange = {},
                    onTokenChange = {},
                    onPromptChange = {},
                    onTerminologyChange = {},
                    onToggleTokenVisibility = {},
                    onTestConnection = {},
                    onSave = {}
                )
            }
        }

        composeRule.onNodeWithText("Test Connection").assertIsNotEnabled()
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun speechSectionShowsSuccessResultAndEnablesActionsWhenConfigured() {
        composeRule.setContent {
            MaterialTheme {
                SpeechRecognitionSection(
                    state = AppState(aiBuilderConnectionOK = true),
                    aiBuilderBaseURL = "https://builder.example.com",
                    aiBuilderToken = "token",
                    aiBuilderCustomPrompt = "",
                    aiBuilderTerminology = "",
                    showAIBuilderToken = false,
                    onBaseUrlChange = {},
                    onTokenChange = {},
                    onPromptChange = {},
                    onTerminologyChange = {},
                    onToggleTokenVisibility = {},
                    onTestConnection = {},
                    onSave = {}
                )
            }
        }

        composeRule.onNodeWithText("Test Connection").assertIsEnabled()
        composeRule.onNodeWithText("Save").assertIsEnabled()
        composeRule.onNodeWithText("Connected successfully").assertIsDisplayed()
    }

    @Test
    fun connectionProfileSectionShowsCurrentSshProfileSummary() {
        val profile = HostProfile(
            id = "ssh-1",
            name = "VPS OpenCode",
            transport = HostTransport.SSH_TUNNEL,
            serverUrl = "http://127.0.0.1:4096",
            ssh = SshTunnelConfig(host = "gateway.example.com", port = 8006, username = "opencode", remotePort = 19001)
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

        composeRule.onNodeWithText("VPS OpenCode").assertIsDisplayed()
        composeRule.onNodeWithText("gateway.example.com:8006 -> :19001").assertIsDisplayed()
        composeRule.onNodeWithText("SSH Tunnel").assertIsDisplayed()
        composeRule.onNodeWithText("Manage Profiles").assertIsDisplayed()
    }

    @Test
    fun hostProfileEditorShowsSshFieldsAndPublicKey() {
        val profile = HostProfile(
            id = "ssh-1",
            name = "VPS OpenCode",
            transport = HostTransport.SSH_TUNNEL,
            serverUrl = "http://127.0.0.1:4096",
            ssh = SshTunnelConfig(host = "gateway.example.com", port = 8006, username = "opencode", remotePort = 19001)
        )

        composeRule.setContent {
            MaterialTheme {
                HostProfileEditorDialog(
                    initial = profile,
                    publicKey = "ssh-rsa AAAATEST opencode-android",
                    onDismiss = {},
                    onSave = { _, _ -> },
                    onCopyPublicKey = {},
                    onRotateKey = {}
                )
            }
        }

        composeRule.onNodeWithText("SSH gateway host").assertIsDisplayed()
        composeRule.onNodeWithText("Assigned remote port").assertIsDisplayed()
        composeRule.onNodeWithText("Device public key").assertIsDisplayed()
        composeRule.onNodeWithTag("host.editor.ssh.publicKey").assertIsDisplayed()
        composeRule.onNodeWithText("Copy public key").assertIsDisplayed()
    }

    @Test
    fun hostProfileEditorSwitchesBetweenDirectAndSshModes() {
        val profile = HostProfile.defaultDirect("http://localhost:4096")

        composeRule.setContent {
            MaterialTheme {
                HostProfileEditorDialog(
                    initial = profile,
                    publicKey = "ssh-rsa AAAATEST opencode-android",
                    onDismiss = {},
                    onSave = { _, _ -> },
                    onCopyPublicKey = {},
                    onRotateKey = {}
                )
            }
        }

        composeRule.onNodeWithText("Server URL").assertIsDisplayed()
        composeRule.onNodeWithTag("host.editor.transport.ssh").performClick()
        composeRule.onNodeWithText("SSH gateway host").assertIsDisplayed()
    }
}
