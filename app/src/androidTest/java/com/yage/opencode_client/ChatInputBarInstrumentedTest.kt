package com.yage.opencode_client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.yage.opencode_client.ui.chat.ChatInputBar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatInputBarInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun busyInputShowsStopAndKeepsSendEnabled() {
        composeRule.setContent {
            MaterialTheme {
                ChatInputBar(
                    text = "hello",
                    isBusy = true,
                    isRecording = false,
                    isTranscribing = false,
                    hasPreservedSpeechAudio = false,
                    isRetryingSpeech = false,
                    isSpeechConfigured = true,
                    onTextChange = {},
                    onSend = {},
                    onAbort = {},
                    onAbortSpeech = {},
                    onRetrySpeech = {},
                    onToggleRecording = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("Stop").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Speech").assertIsEnabled()
    }

    @Test
    fun readyInputEnablesSendAndSpeechCallbacks() {
        var sendClicks = 0
        var speechClicks = 0

        composeRule.setContent {
            MaterialTheme {
                ChatInputBar(
                    text = "hello",
                    isBusy = false,
                    isRecording = false,
                    isTranscribing = false,
                    hasPreservedSpeechAudio = false,
                    isRetryingSpeech = false,
                    isSpeechConfigured = true,
                    onTextChange = {},
                    onSend = { sendClicks++ },
                    onAbort = {},
                    onAbortSpeech = {},
                    onRetrySpeech = {},
                    onToggleRecording = { speechClicks++ }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
        composeRule.onNodeWithContentDescription("Speech").assertIsEnabled().performClick()

        assertEquals(1, sendClicks)
        assertEquals(1, speechClicks)
    }
}
