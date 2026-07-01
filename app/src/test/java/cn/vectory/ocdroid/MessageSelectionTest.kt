package cn.vectory.ocdroid

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies that user and AI messages use SelectionContainer for long-press copy.
 * ChatScreen.kt TextPart (user + AI) and ReasoningCard should wrap content in SelectionContainer.
 */
class MessageSelectionTest {

    @Test
    fun `chat package keeps SelectionContainer for message copy`() {
        val chatDir = (
            File("app/src/main/java/cn/vectory/ocdroid/ui/chat").takeIf { it.exists() }
                ?: File("src/main/java/cn/vectory/ocdroid/ui/chat")
        )
        val contents = chatDir
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        assertTrue(
            "Chat UI must keep SelectionContainer for user/AI message copy",
            contents.contains("SelectionContainer")
        )
    }
}
