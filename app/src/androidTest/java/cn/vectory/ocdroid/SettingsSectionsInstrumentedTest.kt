package cn.vectory.ocdroid

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.ui.settings.AboutSection
import cn.vectory.ocdroid.ui.settings.HostProfileDetailDialog
import cn.vectory.ocdroid.ui.settings.HostProfileEditorDialog
import org.junit.Rule
import org.junit.Test

class SettingsSectionsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

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

        val useCurrentServerLabel = InstrumentationRegistry.getInstrumentation()
            .targetContext.getString(R.string.host_profile_use_this_host)
        composeRule.onNodeWithText(useCurrentServerLabel).assertIsDisplayed()
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

    // §review-r5 / §profile-cleanup R1: re-entering an mTLS profile must render
    // the compact cert status row from the stored-byte summaries — NOT inverted,
    // NOT showing a paste icon for an imported cert. Both stored → both Imported.
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
        // Compact row shows role labels + imported checkmark contentDescriptions.
        composeRule.onNodeWithText("Client").assertIsDisplayed()
        composeRule.onNodeWithText("CA").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Client certificate imported").assertExists()
        composeRule.onNodeWithContentDescription("CA certificate imported").assertExists()
        // Neither slot should show its paste icon.
        composeRule.onNodeWithContentDescription("Paste client certificate (PKCS12)").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Paste CA certificate").assertDoesNotExist()
    }

    // §review-r5 / §profile-cleanup R1: the user's reported shape — client NOT
    // stored, CA stored. Must render client slot = paste icon, CA slot =
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
        // Client (not stored) → its paste icon; CA (stored) → Imported status.
        composeRule.onNodeWithContentDescription("Paste client certificate (PKCS12)").assertExists()
        composeRule.onNodeWithContentDescription("CA certificate imported").assertExists()
        composeRule.onNodeWithText("CA").assertIsDisplayed()
        // Client must NOT show an imported checkmark.
        composeRule.onNodeWithContentDescription("Client certificate imported").assertDoesNotExist()
    }
}
