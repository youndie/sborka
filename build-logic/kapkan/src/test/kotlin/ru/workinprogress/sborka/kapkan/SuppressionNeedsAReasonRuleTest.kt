package ru.workinprogress.sborka.kapkan

import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SuppressionNeedsAReasonRuleTest {
    @Test
    fun `a kapkan suppression with no second string is a defect`() {
        val code =
            """
            @Suppress("ktlint:kapkan:swallowed-failure")
            fun handle() {
                runCatching { send() }
            }
            """.trimIndent()
        assertEquals(listOf("kapkan:suppression-needs-a-reason"), lint(code).ids())
    }

    @Test
    fun `a one-word reason is a label, not a reason`() {
        val code =
            """
            @Suppress("ktlint:kapkan:swallowed-failure", "legacy")
            fun handle() {
                runCatching { send() }
            }
            """.trimIndent()
        assertEquals(listOf("kapkan:suppression-needs-a-reason"), lint(code).ids())
    }

    @Test
    fun `a three-word sentence that says nothing is refused by name`() {
        val code =
            """
            @Suppress("ktlint:kapkan:swallowed-failure", "we need this")
            fun handle() {
                runCatching { send() }
            }
            """.trimIndent()
        assertEquals(listOf("kapkan:suppression-needs-a-reason"), lint(code).ids())
    }

    @Test
    fun `a reason is enough`() {
        val code =
            """
            @Suppress("ktlint:kapkan:swallowed-failure", "closing twice is not a failure anybody acts on")
            fun handle() {
                runCatching { send() }
            }
            """.trimIndent()
        assertEquals(emptyList<String>(), lint(code).ids())
    }

    @Test
    fun `a kotlin diagnostic beside the id is not a reason`() {
        // `UNCHECKED_CAST` is one word and belongs to the compiler, not to the reader. The word count
        // is what separates the two without a list of every diagnostic Kotlin has.
        val code =
            """
            @Suppress("UNCHECKED_CAST", "ktlint:kapkan:swallowed-failure")
            fun handle() {
                runCatching { send() }
            }
            """.trimIndent()
        assertEquals(listOf("kapkan:suppression-needs-a-reason"), lint(code).ids())
    }

    @Test
    fun `a suppression of somebody else's rule is not this rule's business`() {
        // WITH THE STANDARD RULES LOADED, and that is not incidental: ktlint's own
        // `internal:ktlint-suppression` refuses an id naming a rule nobody loaded, so a `standard:`
        // suppression linted by kapkan alone fails for a reason that has nothing to do with this
        // rule. Which is the property `SuppressionIsTheCanaryTest` is built on.
        val code =
            """
            @Suppress("ktlint:standard:no-wildcard-imports")
            fun handle() {
                send()
            }
            """.trimIndent() + "\n"
        val rules = KapkanRuleSetProvider().getRuleProviders() + StandardRuleSetProvider().getRuleProviders()
        assertEquals(emptyList<String>(), lint(code, rules = rules).ids())
    }

    @Test
    fun `an ordinary kotlin suppression is left alone`() {
        val code =
            """
            @Suppress("UNCHECKED_CAST")
            fun handle() {
                send()
            }
            """.trimIndent()
        assertEquals(emptyList<String>(), lint(code).ids())
    }
}
