package cn.vectory.ocdroid.detekt.rules

import com.intellij.psi.PsiElement
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * §wave0-ocdroid-2026-08-03 architecture boundary: raw
 * `androidx.compose.material3.AlertDialog` banned. Use shared dialog primitives
 * (`AppConfirmDialog` / `AppFormDialog`) instead.
 *
 * Per `docs/specs/ui-style-spec.md`, all overlay surfaces must use the shared
 * composables in `ui/theme/`. Raw `AlertDialog` imports scatter dialog style
 * and prevent centralised UX updates.
 *
 * Reports any `KtImportDirective` whose `importedFqName` is exactly
 * `androidx.compose.material3.AlertDialog` (NOT `AlertDialogDefaults` or other
 * siblings) and whose containing file path contains `/ui/` but is NOT one of
 * the two whitelist files (`AppConfirmDialog.kt`, `AppFormDialog.kt`).
 */
class NoRawAlertDialogRule(config: Config) : Rule(
    config,
    "Raw androidx.compose.material3.AlertDialog is banned. Use " +
        "ui/theme/AppConfirmDialog.kt or AppFormDialog.kt (shared dialog " +
        "primitive) instead.",
) {
    override fun visitImportDirective(import: KtImportDirective) {
        super.visitImportDirective(import)
        if (import.isInTestSource()) return
        val fqName = import.importedFqName?.asString() ?: return
        if (fqName != "androidx.compose.material3.AlertDialog") return
        val path = (import.containingFile as? KtFile)?.virtualFile?.path ?: return
        if (!path.contains("/ui/")) return
        if (isWhitelistedDialogFile(path)) return
        report(
            Finding(
                Entity.from(import),
                "Raw androidx.compose.material3.AlertDialog is banned. Use " +
                    "ui/theme/AppConfirmDialog.kt or AppFormDialog.kt (shared " +
                    "dialog primitive) instead.",
            )
        )
    }
}

private fun isWhitelistedDialogFile(path: String): Boolean {
    return path.endsWith("/ui/theme/AppConfirmDialog.kt") ||
        path.endsWith("/ui/theme/AppFormDialog.kt")
}

private fun PsiElement.isInTestSource(): Boolean {
    val file = containingFile as? KtFile ?: return false
    val path = file.virtualFile?.path ?: return false
    return path.contains("/test/") || path.contains("/androidTest/")
}
