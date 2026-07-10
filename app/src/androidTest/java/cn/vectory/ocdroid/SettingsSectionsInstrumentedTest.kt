package cn.vectory.ocdroid

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.settings.AboutSection
import cn.vectory.ocdroid.ui.settings.ConnectionProfileSection
import cn.vectory.ocdroid.ui.settings.HostProfileDetailDialog
import cn.vectory.ocdroid.ui.settings.HostProfileEditorDialog
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
                // §0.6.2 androidTest-compile-fix: ConnectionProfileSection's
                // signature gained groupProfileCount / cachedSessionCount /
                // onStatsClick (§grouping-rewrite 项 2 — the clickable group-stats
                // line under the URL row that opens the cache-management popup).
                // The pre-existing test call omitted them →
                // connectedDebugAndroidTest failed to compile, blocking the whole
                // androidTest source set (including the new 0-shrink gate). Pass
                // benign values so the "current profile summary" assertion still
                // holds: solo profile (count=1), no cached sessions, no-op click.
                ConnectionProfileSection(
                    profile = profile,
                    connectionState = ConnectionState(isConnected = true, serverVersion = "1.0.0"),
                    groupProfileCount = 1,
                    cachedSessionCount = 0,
                    onStatsClick = {},
                    onManageProfiles = {}
                )
            }
        }

        composeRule.onNodeWithText("OpenCode Server").assertIsDisplayed()
        composeRule.onNodeWithText("https://opencode.example.com").assertIsDisplayed()
        // §mtls-clipboard: the manage-profiles affordance is an icon-only
        // IconButton (KeyboardArrowRight) whose label lives in contentDescription,
        // not visible Text — assert on the contentDescription, not text.
        composeRule.onNodeWithContentDescription("Manage Connections").assertIsDisplayed()
    }

    @Test
    fun hostProfileEditorShowsServerUrlAndAuthFields() {
        val profile = HostProfile.defaultDirect("http://localhost:4096")

        composeRule.setContent {
            MaterialTheme {
                HostProfileEditorDialog(
                    initial = profile,
                    onDismiss = {},
                    // onSave arity is Function11 (profile, basicAuthPassword,
                    // basicAuthEdited, tunnelPassword, tunnelEdited, mtlsEnabled,
                    // stagedP12, caStage, p12Password, p12PasswordEdited, hasImportedP12).
                    onSave = { _, _, _, _, _, _, _, _, _, _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("Server URL").assertIsDisplayed()
    }

    @Test
    fun hostProfileDetailShowsUseActionForProfile() {
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
                    onEdit = {}
                )
            }
        }

        composeRule.onNodeWithText("Use This Host").assertIsDisplayed()
    }

    @Test
    fun aboutSectionShowsDynamicVersion() {
        composeRule.setContent {
            MaterialTheme {
                AboutSection()
            }
        }

        // BuildConfig.VERSION_NAME is injected at build time; just assert the
        // prefix renders so we know the About section uses the dynamic value.
        composeRule.onNodeWithText("OC Droid").assertIsDisplayed()
    }

    // §review-r5 (Bug B regression): re-entering an mTLS profile must render the
    // cert slots from the stored-byte summaries — NOT inverted, NOT showing a
    // paste button for an imported cert. Both slots present here → both Imported.
    @Test
    fun mtlsEditorSlots_renderImportedStatusWhenBothStored() {
        val profile = HostProfile(
            id = "p", name = "X", serverUrl = "https://x.example",
            mtlsEnabled = true, clientCertId = "cid",
        )
        composeRule.setContent {
            MaterialTheme {
                HostProfileEditorDialog(
                    initial = profile,
                    onDismiss = {},
                    onSave = { _, _, _, _, _, _, _, _, _, _, _ -> },
                    initialHasCa = true,
                    initialClientSummary = "CN=client" to 1234,
                    initialCaSummary = "CN=opencode CA" to 5678,
                )
            }
        }
        composeRule.waitForIdle()
        // Both slots show the Imported status line (subject · size); neither shows
        // its paste button.
        composeRule.onNodeWithText("Client certificate: CN=client · 1234 B").assertExists()
        composeRule.onNodeWithText("CA certificate: CN=opencode CA · 5678 B").assertExists()
    }

    // §review-r5 (Bug B regression): the user's reported shape — client NOT
    // stored, CA stored. Must render client slot = paste button, CA slot =
    // Imported status (the user saw this inverted). Verifies no inversion.
    @Test
    fun mtlsEditorSlots_clientMissingCaPresent_notInverted() {
        val profile = HostProfile(
            id = "p", name = "X", serverUrl = "https://x.example",
            mtlsEnabled = true, clientCertId = "cid",
        )
        composeRule.setContent {
            MaterialTheme {
                HostProfileEditorDialog(
                    initial = profile,
                    onDismiss = {},
                    onSave = { _, _, _, _, _, _, _, _, _, _, _ -> },
                    initialHasCa = true,
                    initialClientSummary = null,
                    initialCaSummary = "CN=opencode CA" to 5678,
                )
            }
        }
        composeRule.waitForIdle()
        // Client (not stored) → its paste button; CA (stored) → Imported status.
        composeRule.onNodeWithText("Paste client certificate (PKCS12)").assertExists()
        composeRule.onNodeWithText("CA certificate: CN=opencode CA · 5678 B").assertExists()
    }
}
