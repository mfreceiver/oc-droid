package cn.vectory.ocdroid.detekt.rules

import com.intellij.psi.PsiElement
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * §U-MN6 (Batch 3): authority-slice direct-write gate.
 *
 * [cn.vectory.ocdroid.ui.StoreState.authority] is the SINGLE authoritative
 * session-status source of truth. The ONLY legitimate writer is the PURE
 * reducer [cn.vectory.ocdroid.ui.reduceAuthority] (in AuthorityReducer.kt),
 * which returns a fresh StoreState via `state.copy(authority = ...)`.
 *
 * Any OTHER `copy(authority = ...)` — e.g. a caller that runs the reducer then
 * re-assigns the authority slice via `snapshot.copy(authority = reducerOutput.authority)`
 * — bypasses the reducer's single-writer discipline (the caller could later
 * diverge by dropping/altering slices). The BackgroundUnreadPoller:236 site
 * was the historical offender; it was refactored in U-MN6 to derive from the
 * reducer output directly.
 *
 * Reports any `copy(authority = ...)` named argument outside the reducer
 * (file-path allowlist AuthorityReducer.kt OR function-name allowlist
 * `reduceAuthority`). Test sources are excluded.
 *
 * See maintainability-fix-plan §P6.
 */
class AuthorityDirectWriteRule(config: Config) : Rule(
    config,
    "Direct writes to StoreState.authority via copy(authority = ...) are " +
        "forbidden outside reduceAuthority (AuthorityReducer.kt). authority " +
        "is the authority-reducer-owned slice; direct writes reintroduce the " +
        "dual-write hazard the single-CAS refactor closed.",
) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.isInTestSource()) return
        val callee = (expression.calleeExpression as? KtNameReferenceExpression)
            ?.getReferencedName()
        if (callee != "copy") return
        val offending = expression.valueArguments.firstOrNull { arg ->
            arg.getArgumentName()?.asName?.asString() == "authority"
        } ?: return
        if (isInsideAllowedSite(expression)) return
        report(
            Finding(
                Entity.from(offending),
                "Named-argument write via copy(authority = ...) is forbidden " +
                    "outside reduceAuthority (AuthorityReducer.kt). Use the " +
                    "reducer's output directly instead of re-assigning the " +
                    "authority slice.",
            )
        )
    }

    /**
     * Dual allowlist (defense-in-depth, harder to bypass than either alone):
     *  - FILE-PATH: containing file path contains "AuthorityReducer.kt"
     *    (covers reduceAuthority + ALL its private helpers in that file —
     *    the single file that owns authority writes).
     *  - FUNCTION-NAME: lexically nested inside a function named
     *    "reduceAuthority" (covers detekt unit-test synthetic snippets whose
     *    virtualFile path is non-standard, AND any future renamed-but-still-
     *    reducer function until the file-path is updated).
     */
    private fun isInsideAllowedSite(element: PsiElement): Boolean {
        // (a) file-path allowlist
        val file = element.containingFile as? KtFile
        val path = file?.virtualFile?.path
        if (path != null && path.contains("AuthorityReducer.kt")) return true
        // (b) function-name allowlist (lexical walk up the parent chain)
        var parent: PsiElement? = element.parent
        while (parent != null) {
            if (parent is KtNamedFunction && parent.name == "reduceAuthority") return true
            parent = parent.parent
        }
        return false
    }
}

/** Mirrors SessionStatusDirectWriteRule.isInTestSource: skip test/androidTest
 *  so fixtures that build arbitrary StoreState (e.g. AuthorityReducerTest's
 *  `s.copy(authority = ...)`) do not false-positive. */
private fun PsiElement.isInTestSource(): Boolean {
    val file = containingFile as? KtFile ?: return false
    val path = file.virtualFile?.path ?: return false
    return path.contains("/test/") || path.contains("/androidTest/")
}
