package cn.vectory.ocdroid.detekt.rules

import com.intellij.psi.PsiElement
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * §wave0-ocdroid-2026-08-03 architecture boundary: data layer must not depend
 * on UI — prevents reverse coupling.
 *
 * Files under `/data/` must not import `cn.vectory.ocdroid.ui.*`. The allowed
 * dependency direction is UI → data; reverse coupling breaks layering and
 * creates circular-dependency risk at the module level.
 *
 * Reports any `KtImportDirective` whose `importedFqName` starts with
 * `cn.vectory.ocdroid.ui.` and whose containing file path contains `/data/`.
 */
class DataMustNotImportUiRule(config: Config) : Rule(
    config,
    "Data layer must not import the UI layer. UI→data is the only allowed " +
        "direction; reverse coupling breaks layering.",
) {
    override fun visitImportDirective(import: KtImportDirective) {
        super.visitImportDirective(import)
        if (import.isInTestSource()) return
        val fqName = import.importedFqName?.asString() ?: return
        if (!fqName.startsWith("cn.vectory.ocdroid.ui.")) return
        val path = (import.containingFile as? KtFile)?.virtualFile?.path ?: return
        if (!path.contains("/data/")) return
        report(
            Finding(
                Entity.from(import),
                "Data layer must not import the UI layer ($fqName). UI→data is " +
                    "the only allowed direction; reverse coupling breaks layering.",
            )
        )
    }
}

private fun PsiElement.isInTestSource(): Boolean {
    val file = containingFile as? KtFile ?: return false
    val path = file.virtualFile?.path ?: return false
    return path.contains("/test/") || path.contains("/androidTest/")
}
