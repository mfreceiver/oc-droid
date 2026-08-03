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
 * §wave0-ocdroid-2026-08-03: specs for the UI-layer-import-data-api gate rule.
 *
 * Verifies the rule flags imports of `cn.vectory.ocdroid.data.api.*` or
 * `retrofit2.*` in files whose path contains `/ui/`, and permits them outside
 * `/ui/`.
 *
 * NOTE: the rule's path guard (`/ui/` in the virtual file path) is the core
 * behavior under test. We rename the [LightVirtualFile] after compilation to
 * simulate a real project file path. Integration-level `:app:detekt` green
 * is the ultimate proof.
 */
class UiMustNotImportDataApiRuleTest {
    private val rule = UiMustNotImportDataApiRule(Config.empty)

    private fun compileUiFile(content: String, relativePath: String): KtFile {
        val filePath = "/app/src/main/java/cn/vectory/ocdroid/ui/$relativePath"
        val file = compileContentForTest(content, Paths.get(filePath))
        (file.virtualFile as LightVirtualFile).rename(null, filePath.trimStart('/'))
        return file
    }

    private fun compileNonUiFile(content: String): KtFile {
        val filePath = "/app/src/main/java/cn/vectory/ocdroid/data/repository/Foo.kt"
        val file = compileContentForTest(content, Paths.get(filePath))
        // No rename needed — we test that the rule does NOT fire
        return file
    }

    @Test
    fun `flags data-api import in ui file`() {
        val file = compileUiFile(
            """
            package cn.vectory.ocdroid.ui.chat
            import cn.vectory.ocdroid.data.api.SessionApi
            class Foo
            """.trimIndent(),
            "chat/Foo.kt",
        )
        val findings = rule.lint(file)
        assertTrue(
            "expected a finding for data.api import in /ui/ file",
            findings.any { it.message.contains("cn.vectory.ocdroid.data.api.SessionApi") },
        )
    }

    @Test
    fun `flags retrofit2 import in ui file`() {
        val file = compileUiFile(
            """
            package cn.vectory.ocdroid.ui.chat
            import retrofit2.Call
            class Foo
            """.trimIndent(),
            "chat/Foo.kt",
        )
        val findings = rule.lint(file)
        assertTrue(
            "expected a finding for retrofit2 import in /ui/ file",
            findings.any { it.message.contains("retrofit2.Call") },
        )
    }

    @Test
    fun `does not flag data-api import in non-ui file`() {
        val file = compileNonUiFile(
            """
            package cn.vectory.ocdroid.data.repository
            import cn.vectory.ocdroid.data.api.SessionApi
            class Foo
            """.trimIndent(),
        )
        val findings = rule.lint(file)
        assertEquals(
            "non-ui path should have 0 findings",
            0,
            findings.size,
        )
    }
}
