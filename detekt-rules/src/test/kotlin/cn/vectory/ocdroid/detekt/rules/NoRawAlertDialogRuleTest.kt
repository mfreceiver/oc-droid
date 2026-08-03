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
 * §wave0-ocdroid-2026-08-03: specs for the raw-AlertDialog ban rule.
 *
 * Verifies the rule flags imports of `androidx.compose.material3.AlertDialog`
 * in files under `/ui/` but outside the two whitelist files
 * (AppConfirmDialog.kt / AppFormDialog.kt), and permits the import in the
 * whitelist files or outside `/ui/`.
 *
 * NOTE: the rule's path guard (`/ui/` + whitelist check in the virtual file
 * path) is the core behavior under test. We rename the [LightVirtualFile]
 * after compilation to simulate real project file paths. Integration-level
 * `:app:detekt` green is the ultimate proof.
 */
class NoRawAlertDialogRuleTest {
    private val rule = NoRawAlertDialogRule(Config.empty)

    private fun compileAtPath(content: String, relativePath: String): KtFile {
        val filePath = "/app/src/main/java/cn/vectory/ocdroid/$relativePath"
        val file = compileContentForTest(content, Paths.get(filePath))
        (file.virtualFile as LightVirtualFile).rename(null, filePath.trimStart('/'))
        return file
    }

    @Test
    fun `flags AlertDialog import in ui file`() {
        val file = compileAtPath(
            """
            package cn.vectory.ocdroid.ui.chat
            import androidx.compose.material3.AlertDialog
            class Foo
            """.trimIndent(),
            "ui/chat/Foo.kt",
        )
        val findings = rule.lint(file)
        assertTrue(
            "expected a finding for AlertDialog import in /ui/ file",
            findings.any { it.message.contains("AlertDialog") },
        )
    }

    @Test
    fun `does not flag AlertDialogDefaults import`() {
        val file = compileAtPath(
            """
            package cn.vectory.ocdroid.ui.chat
            import androidx.compose.material3.AlertDialogDefaults
            class Foo
            """.trimIndent(),
            "ui/chat/Foo.kt",
        )
        val findings = rule.lint(file)
        assertEquals(
            "AlertDialogDefaults import should have 0 findings (false-positive guard)",
            0,
            findings.size,
        )
    }

    @Test
    fun `does not flag AlertDialog import in AppConfirmDialog`() {
        val file = compileAtPath(
            """
            package cn.vectory.ocdroid.ui.theme
            import androidx.compose.material3.AlertDialog
            fun ConfirmDialog() {}
            """.trimIndent(),
            "ui/theme/AppConfirmDialog.kt",
        )
        val findings = rule.lint(file)
        assertEquals(
            "AppConfirmDialog.kt whitelist should have 0 findings",
            0,
            findings.size,
        )
    }

    @Test
    fun `does not flag AlertDialog import in AppFormDialog`() {
        val file = compileAtPath(
            """
            package cn.vectory.ocdroid.ui.theme
            import androidx.compose.material3.AlertDialog
            fun FormDialog() {}
            """.trimIndent(),
            "ui/theme/AppFormDialog.kt",
        )
        val findings = rule.lint(file)
        assertEquals(
            "AppFormDialog.kt whitelist should have 0 findings",
            0,
            findings.size,
        )
    }

    @Test
    fun `does not flag AlertDialog import outside ui`() {
        val file = compileAtPath(
            """
            package cn.vectory.ocdroid.data
            import androidx.compose.material3.AlertDialog
            class Foo
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
