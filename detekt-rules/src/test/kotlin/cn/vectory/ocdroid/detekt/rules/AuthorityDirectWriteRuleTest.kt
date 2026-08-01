package cn.vectory.ocdroid.detekt.rules

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §U-MN6 (Batch 3): specs for the authority-slice direct-write gate rule.
 *
 * Verifies the rule flags `copy(authority = ...)` outside the allowlisted
 * `reduceAuthority` function, and permits the legitimate call sites.
 *
 * NOTE: the test-source path exclusion (via [isInTestSource]) and the
 * file-path allowlist cannot be unit-tested here because detekt-test's
 * `compileContentForTest` creates synthetic files with non-standard paths.
 * Those behaviors are verified at the integration level: `:app:detekt` is
 * green (0 findings) even though test fixtures like AuthorityReducerTest
 * use `s.copy(authority = ...)`.
 */
class AuthorityDirectWriteRuleTest {
    private val rule = AuthorityDirectWriteRule(Config.empty)

    @Test
    fun `flags copy with authority arg outside reduceAuthority`() {
        val code = """
            data class StoreState(val authority: String)
            fun foo(snapshot: StoreState, withStatus: StoreState): StoreState =
                snapshot.copy(authority = withStatus.authority)
        """.trimIndent()
        val findings = rule.lint(code)
        assertTrue("expected a finding for copy(authority=...) outside reducer",
            findings.any { it.message.contains("authority") })
    }

    @Test
    fun `flags copy with authority arg inside mutateState lambda`() {
        val code = """
            data class StoreState(val authority: String)
            fun mutateState(b: () -> StoreState): StoreState = b()
            fun foo(snapshot: StoreState, withStatus: StoreState): StoreState =
                mutateState { snapshot.copy(authority = withStatus.authority) }
        """.trimIndent()
        val findings = rule.lint(code)
        assertTrue("expected a finding inside mutateState lambda",
            findings.any { it.message.contains("authority") })
    }

    @Test
    fun `allows copy with authority arg inside reduceAuthority`() {
        val code = """
            data class StoreState(val authority: String)
            fun reduceAuthority(state: StoreState, op: String): StoreState =
                state.copy(authority = "cleaned")
        """.trimIndent()
        val findings = rule.lint(code)
        assertEquals("reduceAuthority body should have 0 findings", 0, findings.size)
    }

    @Test
    fun `does not flag copy of unrelated field`() {
        val code = """
            data class StoreState(val authority: String, val sessionList: String)
            fun foo(s: StoreState): StoreState = s.copy(sessionList = "x")
        """.trimIndent()
        val findings = rule.lint(code)
        assertEquals("non-authority copy should have 0 findings", 0, findings.size)
    }
}
