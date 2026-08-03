package cn.vectory.ocdroid.detekt.rules

import com.intellij.testFramework.LightVirtualFile
import dev.detekt.api.Config
import dev.detekt.test.lint
import dev.detekt.test.utils.compileContentForTest
import java.nio.file.Paths
import org.jetbrains.kotlin.psi.KtFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §wave0-ocdroid-2026-08-03: specs for the raw-.dp-literal ban rule.
 *
 * Verifies the rule flags `.dp` property access in files under `/ui/` but
 * outside `/ui/theme/`, and permits them in `/ui/theme/` or outside `/ui/`.
 *
 * NOTE: the rule's path guard (`/ui/` AND NOT `/ui/theme/` in the virtual
 * file path) is the core behavior under test. We rename the [LightVirtualFile]
 * after compilation to simulate real project file paths. Integration-level
 * `:app:detekt` green is the ultimate proof.
 */
class NoRawDpLiteralRuleTest {
    private val rule = NoRawDpLiteralRule(Config.empty)

    private fun compileAtPath(content: String, relativePath: String): KtFile {
        val filePath = "/app/src/main/java/cn/vectory/ocdroid/$relativePath"
        val file = compileContentForTest(content, Paths.get(filePath))
        (file.virtualFile as LightVirtualFile).rename(null, filePath.trimStart('/'))
        return file
    }

    @Test
    fun `flags raw dp literal in ui file`() {
        val file = compileAtPath(
            """
            package cn.vectory.ocdroid.ui.chat
            import androidx.compose.ui.unit.dp
            class Foo {
                val x = 16.dp
            }
            """.trimIndent(),
            "ui/chat/Foo.kt",
        )
        val findings = rule.lint(file)
        assertTrue(
            "expected a finding for raw .dp in /ui/ file",
            findings.any { it.message.contains(".dp") },
        )
    }

    @Test
    fun `does not flag raw dp literal in ui-theme file`() {
        val file = compileAtPath(
            """
            package cn.vectory.ocdroid.ui.theme
            import androidx.compose.ui.unit.dp
            val FooDimen = 16.dp
            """.trimIndent(),
            "ui/theme/Dimens.kt",
        )
        val findings = rule.lint(file)
        assertEquals(
            "/ui/theme/ path should have 0 findings",
            0,
            findings.size,
        )
    }

    @Test
    fun `does not flag raw dp literal outside ui`() {
        val file = compileAtPath(
            """
            package cn.vectory.ocdroid.data
            import androidx.compose.ui.unit.dp
            val FooDimen = 16.dp
            """.trimIndent(),
            "data/Foo.kt",
        )
        val findings = rule.lint(file)
        assertEquals(
            "non-ui path should have 0 findings",
            0,
            findings.size,
        )
    }
}
