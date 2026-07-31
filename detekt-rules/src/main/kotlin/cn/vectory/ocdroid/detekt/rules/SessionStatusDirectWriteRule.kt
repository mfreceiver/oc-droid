package cn.vectory.ocdroid.detekt.rules

import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtReferenceExpression

/**
 * §sm-hardening B10 (sole-writer encapsulation gate).
 *
 * [cn.vectory.ocdroid.ui.SessionListState] is intentionally a non-`data class`
 * with a `private set` `sessionStatuses` field. The ONLY legitimate mutation
 * paths live in the same file (AppStateSlices.kt): `withProjection(...)` (the
 * single authority-backed projection entry point) and the hand-written
 * `copy(...)` / `.also { }` blocks inside the class body.
 *
 * Any other direct write to `sessionStatuses` — including
 * `mutateSessionList { sessionStatuses = ... }` or a future regression that
 * re-adds `copy(sessionStatuses = ...)` — bypasses the authority reducer and
 * reintroduces the dual-write race the state-machine refactor eliminated.
 *
 * Reports any `sessionStatuses =` assignment that is not lexically nested
 * inside `withProjection(...)` or inside the `SessionListState` class body.
 *
 * See report ses_04bb9408 §7 (P1: detekt allowlist as defense-in-depth).
 */
class SessionStatusDirectWriteRule(config: Config) : Rule(
    config,
    "Direct writes to SessionListState.sessionStatuses are forbidden outside " +
        "withProjection / the SessionListState class body. " +
        "sessionStatuses is an authority projection; direct writes reintroduce " +
        "the dual-write race the state-machine refactor closed.",
) {
    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        super.visitBinaryExpression(expression)
        if (expression.isInTestSource()) return
        // Only plain `=` assignment (not `+=`, `-=` etc.).
        if (expression.operationReference.getReferencedNameElementType() !== KtTokens.EQ) return
        val left = expression.left ?: return
        if (!left.referencesSessionStatuses()) return
        if (isInsideAllowedSite(expression)) return
        report(
            Finding(
                Entity.from(expression),
                "Direct assignment to sessionStatuses is forbidden outside " +
                    "withProjection / the SessionListState class body. Use " +
                    "withProjection { ... } (authority reducer path) instead.",
            )
        )
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.isInTestSource()) return
        // Defense-in-depth: detect `.copy(sessionStatuses = ...)` named argument.
        // The constructor doesn't expose this param today; this guards a future
        // regression that re-adds it.
        val callee = (expression.calleeExpression as? KtNameReferenceExpression)
            ?.getReferencedName()
        if (callee == "copy") {
            val offending = expression.valueArguments.firstOrNull { arg ->
                arg.getArgumentName()?.asName?.asString() == "sessionStatuses"
            }
            if (offending != null && !isInsideAllowedSite(expression)) {
                report(
                    Finding(
                        Entity.from(offending),
                        "Named-argument write via copy(sessionStatuses = ...) is " +
                            "forbidden outside SessionListState's own hand-written copy. " +
                            "sessionStatuses is an authority projection.",
                    )
                )
            }
        }
    }

    private fun KtExpression.referencesSessionStatuses(): Boolean =
        when (this) {
            is KtNameReferenceExpression -> getReferencedName() == "sessionStatuses"
            is KtReferenceExpression -> text.endsWith("sessionStatuses")
            else -> text.endsWith("sessionStatuses")
        }

    /** True when lexically nested inside `withProjection(...)`, inside a
     *  `copy(...)` that lives in SessionListState's class body, or inside the
     *  SessionListState class body itself (covers the `var` declaration and
     *  any private helper that legitimately touches the private setter). */
    private fun isInsideAllowedSite(element: PsiElement): Boolean {
        var parent: PsiElement? = element.parent
        while (parent != null) {
            if (parent is KtCallExpression) {
                val callee = (parent.calleeExpression as? KtNameReferenceExpression)
                    ?.getReferencedName()
                if (callee == "withProjection") return true
                if (callee == "copy" && parent.isInsideSessionListStateClass()) return true
            }
            if (parent is KtClassBody) {
                val cls = parent.parent as? KtClass
                if (cls?.name == "SessionListState") return true
            }
            parent = parent.parent
        }
        return false
    }

    private fun KtCallExpression.isInsideSessionListStateClass(): Boolean {
        var p: PsiElement? = parent
        while (p != null) {
            if (p is KtClassBody && (p.parent as? KtClass)?.name == "SessionListState") return true
            p = p.parent
        }
        return false
    }
}

/** The sole-writer invariant guards PRODUCTION code only. Test fixtures
 *  ([cn.vectory.ocdroid.ui.controller.SeedFixture] — a data class mirroring the
 *  deleted AppState) legitimately construct arbitrary state including a
 *  `sessionStatuses` map via their own `copy()`. Skip the rule for test source
 *  sets so it does not false-positive on those fixtures. */
private fun PsiElement.isInTestSource(): Boolean {
    val file = containingFile as? org.jetbrains.kotlin.psi.KtFile ?: return false
    val path = file.virtualFile?.path ?: return false
    return path.contains("/test/") || path.contains("/androidTest/")
}
