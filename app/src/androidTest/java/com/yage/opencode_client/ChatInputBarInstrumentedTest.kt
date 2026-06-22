package com.yage.opencode_client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
                    isRecording = false,
                    isTranscribing = false,
                    hasPreservedSpeechAudio = false,
                    isRetryingSpeech = false,
                    speechAudioLevel = 0f,
                    isSpeechConfigured = true,
                    imageAttachments = emptyList(),
                    onTextChange = {},
                    onSend = {},
                    onAddImages = {},
                    onRemoveImage = {},
                    onAbort = {},
                    onAbortSpeech = {},
                    onRetrySpeech = {},
                    onDiscardSpeech = {},
                    onToggleRecording = {}
                )
            }
        }

        composeRule.onNodeWithText("Agent running").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Interrupt agent").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Tap to speak").assertIsEnabled()
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
                    agentActivityText = null,
                    agentStartedAtMillis = null,
                    isRecording = false,
                    isTranscribing = false,
                    hasPreservedSpeechAudio = false,
                    isRetryingSpeech = false,
                    speechAudioLevel = 0f,
                    isSpeechConfigured = true,
                    imageAttachments = emptyList(),
                    onTextChange = {},
                    onSend = { sendClicks++ },
                    onAddImages = {},
                    onRemoveImage = {},
                    onAbort = {},
                    onAbortSpeech = {},
                    onRetrySpeech = {},
                    onDiscardSpeech = {},
                    onToggleRecording = { speechClicks++ }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
        composeRule.onNodeWithContentDescription("Tap to speak").assertIsEnabled().performClick()

        assertEquals(1, sendClicks)
        assertEquals(1, speechClicks)
    }

    @Test
    fun transcribingShowsStopWaitAndAgentMenuSeparately() {
        composeRule.setContent {
            MaterialTheme {
                ChatInputBar(
                    text = "partial transcript",
                    isBusy = true,
                    agentActivityText = null,
                    agentStartedAtMillis = null,
                    isRecording = false,
                    isTranscribing = true,
                    hasPreservedSpeechAudio = false,
                    isRetryingSpeech = false,
                    speechAudioLevel = 0f,
                    isSpeechConfigured = true,
                    imageAttachments = emptyList(),
                    onTextChange = {},
                    onSend = {},
                    onAddImages = {},
                    onRemoveImage = {},
                    onAbort = {},
                    onAbortSpeech = {},
                    onRetrySpeech = {},
                    onDiscardSpeech = {},
                    onToggleRecording = {}
                )
            }
        }

        composeRule.onNodeWithText("Agent running · Transcribing").assertIsDisplayed()
        composeRule.onNodeWithText("Stop transcription wait").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Interrupt agent").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    @Test
    fun preservedAudioShowsRetryAndDiscardActions() {
        composeRule.setContent {
            MaterialTheme {
                ChatInputBar(
                    text = "",
                    isBusy = false,
                    agentActivityText = null,
                    agentStartedAtMillis = null,
                    isRecording = false,
                    isTranscribing = false,
                    hasPreservedSpeechAudio = true,
                    isRetryingSpeech = false,
                    speechAudioLevel = 0f,
                    isSpeechConfigured = true,
                    imageAttachments = emptyList(),
                    onTextChange = {},
                    onSend = {},
                    onAddImages = {},
                    onRemoveImage = {},
                    onAbort = {},
                    onAbortSpeech = {},
                    onRetrySpeech = {},
                    onDiscardSpeech = {},
                    onToggleRecording = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("Retry this segment").assertIsEnabled()
        composeRule.onNodeWithText("Discard audio").assertIsDisplayed()
    }
}
