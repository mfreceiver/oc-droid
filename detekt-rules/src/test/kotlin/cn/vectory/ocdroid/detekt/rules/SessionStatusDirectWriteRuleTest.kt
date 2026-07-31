package cn.vectory.ocdroid.detekt.rules

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §sm-hardening B10: specs for the sole-writer encapsulation gate rule.
 *
 * Verifies the rule flags direct `sessionStatuses =` writes outside the
 * allowlisted `withProjection` / SessionListState-class-body sites, and
 * permits the legitimate call sites.
 *
 * NOTE: the test-source path exclusion (via [isInTestSource]) cannot be
 * unit-tested here because detekt-test's `compileContentForTest` creates
 * synthetic files with non-standard paths. That behavior is verified at the
 * integration level: `:app:detekt` is green (0 findings) even though test
 * fixtures like SeedFixture use `copy(sessionStatuses = ...)`.
 */
class SessionStatusDirectWriteRuleTest {
    private val rule = SessionStatusDirectWriteRule(Config.empty)

    @Test
    fun `flags bare sessionStatuses assignment`() {
        val code = """
            var sessionStatuses: Map<String, String> = emptyMap()
            fun foo() {
                sessionStatuses = mapOf("A" to "busy")
            }
        """.trimIndent()
        val findings = rule.lint(code)
        assertTrue("expected a finding for bare sessionStatuses assignment",
            findings.any { it.message.contains("sessionStatuses") })
    }

    @Test
    fun `flags sessionStatuses assignment inside mutateSessionList lambda`() {
        val code = """
            var sessionStatuses: Map<String, String> = emptyMap()
            fun mutateSessionList(b: () -> Unit) { b() }
            fun foo() {
                mutateSessionList {
                    sessionStatuses = mapOf("A" to "busy")
                }
            }
        """.trimIndent()
        val findings = rule.lint(code)
        assertTrue("expected a finding inside mutateSessionList lambda",
            findings.any { it.message.contains("sessionStatuses") })
    }

    @Test
    fun `allows sessionStatuses assignment inside withProjection`() {
        val code = """
            var sessionStatuses: Map<String, String> = emptyMap()
            fun withProjection(block: () -> Unit) { block() }
            fun foo() {
                withProjection {
                    sessionStatuses = mapOf("A" to "busy")
                }
            }
        """.trimIndent()
        val findings = rule.lint(code)
        assertEquals("withProjection body should have 0 findings", 0, findings.size)
    }

    @Test
    fun `flags copy named-argument write outside SessionListState`() {
        val code = """
            class SessionListState {
                fun copy(sessionStatuses: Map<String, String>): SessionListState = this
            }
            fun foo(state: SessionListState) {
                state.copy(sessionStatuses = mapOf("A" to "busy"))
            }
        """.trimIndent()
        val findings = rule.lint(code)
        assertTrue("expected a finding for copy(sessionStatuses = ...) outside SessionListState",
            findings.any { it.message.contains("copy") })
    }
}
