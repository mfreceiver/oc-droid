package cn.vectory.ocdroid.detekt.rules

import com.intellij.psi.PsiElement
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * §wave0-ocdroid-2026-08-03 architecture boundary: UI layer must not depend on
 * the data/api or networking layer.
 *
 * Files under `/ui/` must not import `cn.vectory.ocdroid.data.api.*` (the data
 * API contract) or `retrofit2.*` (the HTTP transport). Presentation-layer code
 * that talks directly to API interfaces or Retrofit couples itself to the
 * transport; all data access should route through the repository / controller
 * layer instead.
 *
 * Reports any `KtImportDirective` whose `importedFqName` starts with
 * `cn.vectory.ocdroid.data.api` or `retrofit2.` and whose containing file path
 * contains `/ui/`.
 */
class UiMustNotImportDataApiRule(config: Config) : Rule(
    config,
    "UI layer must not import the data/api or retrofit2 layer. This couples " +
        "presentation to transport; route through the repository/controller " +
        "layer instead.",
) {
    override fun visitImportDirective(import: KtImportDirective) {
        super.visitImportDirective(import)
        if (import.isInTestSource()) return
        val fqName = import.importedFqName?.asString() ?: return
        if (!fqName.startsWith("cn.vectory.ocdroid.data.api") &&
            !fqName.startsWith("retrofit2.")
        ) return
        val path = (import.containingFile as? KtFile)?.virtualFile?.path ?: return
        if (!path.contains("/ui/")) return
        report(
            Finding(
                Entity.from(import),
                "UI layer must not import the data/api or retrofit2 layer " +
                    "($fqName). This couples presentation to transport; route " +
                    "through the repository/controller layer instead.",
            )
        )
    }
}

private fun PsiElement.isInTestSource(): Boolean {
    val file = containingFile as? KtFile ?: return false
    val path = file.virtualFile?.path ?: return false
    return path.contains("/test/") || path.contains("/androidTest/")
}
