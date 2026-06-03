package com.yage.opencode_client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import com.yage.opencode_client.data.model.MessageWithParts
import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.ui.chat.FileCard
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test

/**
 * Layer 2 (component) test for the read/write tool-card distinction.
 *
 * A read file card and a write file card differ only by icon color, which Compose
 * does not expose to the semantics tree. The render code therefore encodes the
 * read/write nature into the testTag (toolcard.read.* / toolcard.write.*) and the
 * icon contentDescription. This test renders FileCard in isolation with a fake
 * read part and a fake write part and asserts the semantics tree distinguishes
 * them — the cross-cutting prerequisite that layers 2-4 all depend on.
 *
 * No server, no LLM, no real session: pure component test, cost zero.
 */
class FileCardInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun toolPart(tool: String, path: String): Part {
        val payload = """
            [{"info":{"id":"msg_1","role":"assistant","sessionID":"ses_1"},
              "parts":[{"type":"tool","id":"p1","sessionID":"ses_1","messageID":"msg_1",
                        "tool":"$tool","metadata":{"path":"$path"}}]}]
        """.trimIndent()
        return json.decodeFromString<List<MessageWithParts>>(payload)[0].parts[0]
    }

    @Test
    fun readFileCardGetsReadTagAndDescription() {
        composeRule.setContent {
            MaterialTheme {
                FileCard(part = toolPart(tool = "read", path = "src/config.kt"), onFileClick = {})
            }
        }

        composeRule.onNodeWithTag("toolcard.read.config.kt").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Read file config.kt").assertIsDisplayed()
    }

    @Test
    fun writeFileCardGetsWriteTagAndDescription() {
        composeRule.setContent {
            MaterialTheme {
                FileCard(part = toolPart(tool = "write", path = "src/config.kt"), onFileClick = {})
            }
        }

        composeRule.onNodeWithTag("toolcard.write.config.kt").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Write file config.kt").assertIsDisplayed()
    }

    @Test
    fun editFileCardCountsAsWrite() {
        composeRule.setContent {
            MaterialTheme {
                FileCard(part = toolPart(tool = "edit", path = "src/main.kt"), onFileClick = {})
            }
        }

        composeRule.onNodeWithTag("toolcard.write.main.kt").assertIsDisplayed()
    }
}
