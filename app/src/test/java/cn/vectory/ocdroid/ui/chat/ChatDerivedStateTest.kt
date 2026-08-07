package cn.vectory.ocdroid.ui.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import cn.vectory.ocdroid.data.model.ConfigProvider
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.ProviderModel
import cn.vectory.ocdroid.data.model.ProviderModelLimit
import cn.vectory.ocdroid.data.model.ProvidersResponse
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.ComposerState
import cn.vectory.ocdroid.ui.HostState
import cn.vectory.ocdroid.ui.SessionListState
import cn.vectory.ocdroid.ui.SettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §rev-gpt finding 1 regression test: verifies that [rememberChatDerivedState]
 * recomputes [computedContextUsage] when [SettingsState.providers] changes but
 * [renderedMessages] is unchanged.
 *
 * This pins the fix for the "remember(renderedMessages.value)" narrow-memo-key
 * regression (45dfe0db had no memo — a plain val recomputed every composition).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ChatDerivedStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `cachedContextUsageState updates when providers change even if messages are identical`() {
        // ── Arrange: assistant message with tokens + resolved model ───────────
        val assistantMsg = Message(
            id = "a1", role = "assistant",
            tokens = Message.TokenInfo(total = 500),
            model = Message.ModelInfo("test-provider", "test-model"),
        )
        val messages = listOf(
            Message(id = "u1", role = "user"),
            assistantMsg,
        )

        // Initial providers: test-provider/test-model limit=10000 → 5%
        val initialProviders = ProvidersResponse(providers = listOf(
            ConfigProvider(id = "test-provider", models = mapOf(
                "test-model" to ProviderModel(
                    id = "test-model",
                    limit = ProviderModelLimit(context = 10000),
                ),
            )),
        ))

        // Changed providers: same model, limit=2000 → 25%
        val changedProviders = ProvidersResponse(providers = listOf(
            ConfigProvider(id = "test-provider", models = mapOf(
                "test-model" to ProviderModel(
                    id = "test-model",
                    limit = ProviderModelLimit(context = 2000),
                ),
            )),
        ))

        val chatState = mutableStateOf(ChatState(messages = messages))
        val settingsState = mutableStateOf(SettingsState(providers = initialProviders))
        val sessionListState = mutableStateOf(SessionListState())
        val composerState = mutableStateOf(ComposerState())
        val hostState = mutableStateOf(HostState())

        // ── Act: compose rememberChatDerivedState and capture snapshots ───────
        val usageSnapshots = mutableListOf<ContextUsageSnapshot>()

        composeRule.setContent {
            val derived = rememberChatDerivedState(
                routeSessionId = null,
                routeInstance = 0L,
                chatState = chatState,
                sessionListState = sessionListState,
                settingsState = settingsState,
                composerState = composerState,
                hostState = hostState,
                onOpenChatFilePreview = { _, _ -> },
            )
            // Deduplicate: only record when the hash changes
            val lastHash = remember { mutableStateOf(-1) }
            val usage = derived.cachedContextUsageState.value
            val h = usage.hashCode()
            if (lastHash.value != h) {
                lastHash.value = h
                usageSnapshots.add(
                    ContextUsageSnapshot(
                        percentage = usage?.percentage,
                        contextLimit = usage?.contextLimit,
                        providerId = usage?.providerId,
                    ),
                )
            }
        }

        // ── Assert initial: 500/10000 = 5% ───────────────────────────────────
        composeRule.runOnIdle {
            assertEquals("initial snapshot count", 1, usageSnapshots.size)
            val s0 = usageSnapshots[0]
            assertNotNull("initial usage must not be null", s0.percentage)
            assertEquals(0.05f, s0.percentage!!, 0.001f)
            assertEquals(10000, s0.contextLimit)
            assertEquals("test-provider", s0.providerId)
        }

        // ── Act: change providers (keep messages identical) ───────────────────
        composeRule.runOnIdle {
            settingsState.value = SettingsState(providers = changedProviders)
        }

        // ── Assert: recomposition must reflect new providers ──────────────────
        composeRule.runOnIdle {
            assertTrue(
                "must recompute after providers change even if messages don't: " +
                    "snapshots=$usageSnapshots. If this fails, the remember() memo " +
                    "key was too narrow (e.g. 'remember(renderedMessages.value)' " +
                    "skips recompute on provider-only changes)",
                usageSnapshots.size >= 2,
            )
            val latest = usageSnapshots.last()
            assertNotNull("updated usage must not be null", latest.percentage)
            assertEquals("percentage must reflect new limit 2000", 0.25f, latest.percentage!!, 0.001f)
            assertEquals("contextLimit must be 2000", 2000, latest.contextLimit)
            assertEquals("providerId unchanged", "test-provider", latest.providerId)
        }
    }

    /**
     * Lightweight snapshot that avoids full [cn.vectory.ocdroid.ui.ContextUsage]
     * equality (hashCode includes all nullable fields that may flip from null to
     * null on recomposition even when the core values don't change).
     */
    private data class ContextUsageSnapshot(
        val percentage: Float?,
        val contextLimit: Int?,
        val providerId: String?,
    )
}
