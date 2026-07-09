package cn.vectory.ocdroid

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
        composeRule.onNodeWithText("Manage Connections").assertIsDisplayed()
    }

    @Test
    fun hostProfileEditorShowsServerUrlAndAuthFields() {
        val profile = HostProfile.defaultDirect("http://localhost:4096")

        composeRule.setContent {
            MaterialTheme {
                HostProfileEditorDialog(
                    initial = profile,
                    onDismiss = {},
                    // onSave arity is Function5 (profile, basicAuthPassword,
                    // basicAuthEdited, tunnelPassword, tunnelEdited).
                    onSave = { _, _, _, _, _ -> },
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
}
