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
 * §wave0-ocdroid-2026-08-03: specs for the data-layer-must-not-import-ui gate
 * rule.
 *
 * Verifies the rule flags imports of `cn.vectory.ocdroid.ui.*` in files whose
 * path contains `/data/`, and permits them outside `/data/`.
 *
 * NOTE: the rule's path guard (`/data/` in the virtual file path) is the core
 * behavior under test. We rename the [LightVirtualFile] after compilation to
 * simulate a real project file path. Integration-level `:app:detekt` green
 * is the ultimate proof.
 */
class DataMustNotImportUiRuleTest {
    private val rule = DataMustNotImportUiRule(Config.empty)

    private fun compileDataFile(content: String): KtFile {
        val filePath = "/app/src/main/java/cn/vectory/ocdroid/data/repository/SessionRepo.kt"
        val file = compileContentForTest(content, Paths.get(filePath))
        (file.virtualFile as LightVirtualFile).rename(null, filePath.trimStart('/'))
        return file
    }

    private fun compileNonDataFile(content: String): KtFile {
        // Compile to a file under /ui/ (no rename — path won't contain /data/)
        return compileContentForTest(
            content,
            Paths.get("/app/src/main/java/cn/vectory/ocdroid/ui/chat/Foo.kt"),
        )
    }

    @Test
    fun `flags ui import in data file`() {
        val file = compileDataFile(
            """
            package cn.vectory.ocdroid.data.repository
            import cn.vectory.ocdroid.ui.chat.SessionList
            class Foo
            """.trimIndent(),
        )
        val findings = rule.lint(file)
        assertTrue(
            "expected a finding for ui import in /data/ file",
            findings.any { it.message.contains("cn.vectory.ocdroid.ui.chat.SessionList") },
        )
    }

    @Test
    fun `does not flag ui import in non-data file`() {
        val file = compileNonDataFile(
            """
            package cn.vectory.ocdroid.ui.chat
            import cn.vectory.ocdroid.ui.chat.SessionList
            class Foo
            """.trimIndent(),
        )
        val findings = rule.lint(file)
        assertEquals(
            "non-data path should have 0 findings",
            0,
            findings.size,
        )
    }

    @Test
    fun `does not flag non-ui import in data file`() {
        val file = compileDataFile(
            """
            package cn.vectory.ocdroid.data.repository
            import java.util.List
            class Foo
            """.trimIndent(),
        )
        val findings = rule.lint(file)
        assertEquals(
            "non-ui import in /data/ path should have 0 findings",
            0,
            findings.size,
        )
    }
}
