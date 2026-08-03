package cn.vectory.ocdroid.detekt.rules

import com.intellij.psi.PsiElement
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile

/**
 * §wave0-ocdroid-2026-08-03 architecture boundary: raw `.dp` literals banned
 * outside `ui/theme/`; use shared `Dimens` primitives instead.
 *
 * Per `docs/specs/ui-style-spec.md`, all spacing/dimension values must use the
 * shared `Dimens` constants defined in `ui/theme/Dimens.kt`. Raw `.dp` calls
 * (e.g. `16.dp`) scatter magic numbers that are hard to maintain and
 * inconsistent.
 *
 * Reports any `KtDotQualifiedExpression` where the selector text is exactly
 * `"dp"` and the containing file path contains `/ui/` but NOT `/ui/theme/`.
 */
class NoRawDpLiteralRule(config: Config) : Rule(
    config,
    "Raw .dp literal is banned outside ui/theme/. Use a Dimens constant " +
        "(ui/theme/Dimens.kt) instead.",
) {
    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        if (expression.isInTestSource()) return
        if (expression.selectorExpression?.text != "dp") return
        val path = (expression.containingFile as? KtFile)?.virtualFile?.path ?: return
        if (!path.contains("/ui/")) return
        if (path.contains("/ui/theme/")) return
        report(
            Finding(
                Entity.from(expression),
                "Raw .dp literal is banned outside ui/theme/. Use a Dimens " +
                    "constant (ui/theme/Dimens.kt) instead.",
            )
        )
    }
}

private fun PsiElement.isInTestSource(): Boolean {
    val file = containingFile as? KtFile ?: return false
    val path = file.virtualFile?.path ?: return false
    return path.contains("/test/") || path.contains("/androidTest/")
}
