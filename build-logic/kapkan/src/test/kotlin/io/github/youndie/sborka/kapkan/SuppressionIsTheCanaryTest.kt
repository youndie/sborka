package io.github.youndie.sborka.kapkan

import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What happens when kapkan is NOT on the classpath, pinned from the other side.
 *
 * ktlint's own `ktlint-suppression` rule refuses a suppression id that names no loaded rule. That is
 * what makes a suppression the cheapest wiring test there is: a repository whose
 * `@Suppress("ktlint:kapkan:…")` still lints is a repository where the rule set actually arrived, and
 * a release that forgets to publish the jar fails on the suppression instead of going quiet.
 *
 * Both halves are here because only the pair proves anything: the first says the id is accepted when
 * the rule exists, the second says it is refused when it does not.
 */
class SuppressionIsTheCanaryTest {
    private val code =
        """
        @Suppress("ktlint:kapkan:wall-clock", "this is the Clock port; everything else reads it")
        fun t(): Long = System.currentTimeMillis()
        """.trimIndent() + "\n"

    @Test
    fun `with kapkan loaded, ktlint accepts the id and the standard rules say nothing`() {
        val rules = KapkanRuleSetProvider().getRuleProviders() + StandardRuleSetProvider().getRuleProviders()
        val errors = lint(code, rules = rules)
        assertEquals(emptyList<String>(), errors.ids())
    }

    @Test
    fun `without kapkan loaded, the suppression itself fails`() {
        val errors = lint(code, rules = StandardRuleSetProvider().getRuleProviders())
        assertTrue(
            errors.any { it.detail.contains("kapkan:wall-clock") },
            "a suppression naming a rule nobody loaded must fail, and it reported $errors",
        )
    }
}
