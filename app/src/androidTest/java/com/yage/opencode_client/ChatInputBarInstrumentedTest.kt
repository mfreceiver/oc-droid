package com.yage.opencode_client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yage.opencode_client.ui.chat.ChatInputBar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatInputBarInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun busyInputShowsQuietStatusAndKeepsSendEnabled() {
        composeRule.setContent {
            MaterialTheme {
                ChatInputBar(
                    text = "hello",
                    isBusy = true,
                    agentActivityText = null,
                    agentStartedAtMillis = null,
                    imageAttachments = emptyList(),
                    onTextChange = {},
                    onSend = {},
                    onAddImages = {},
                    onRemoveImage = {},
                    onAbort = {},
                )
            }
        }

        composeRule.onNodeWithText("Agent running").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Interrupt agent").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send").assertIsEnabled()
    }

    @Test
    fun readyInputEnablesSend() {
        var sendClicks = 0

        composeRule.setContent {
            MaterialTheme {
                ChatInputBar(
                    text = "hello",
                    isBusy = false,
                    agentActivityText = null,
                    agentStartedAtMillis = null,
                    imageAttachments = emptyList(),
                    onTextChange = {},
                    onSend = { sendClicks++ },
                    onAddImages = {},
                    onRemoveImage = {},
                    onAbort = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Send").assertIsEnabled().performClick()

        assertEquals(1, sendClicks)
    }
}
