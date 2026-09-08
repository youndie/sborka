package io.github.youndie.sborka.kapkan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SwallowedFailureRuleTest {
    @Test
    fun `a runCatching that is not the last statement is discarded`() {
        val code =
            """
            fun handle() {
                runCatching { send() }
                next()
            }
            """.trimIndent()
        assertEquals(listOf("kapkan:swallowed-failure"), lint(code).ids())
    }

    @Test
    fun `a runCatching last in a block body is discarded`() {
        // A `fun` with `{ }` throws its last expression away — that is what the braces mean, as
        // opposed to `=`. No type is needed to know it.
        val code =
            """
            fun handle() {
                runCatching { send() }
            }
            """.trimIndent()
        assertEquals(listOf("kapkan:swallowed-failure"), lint(code).ids())
    }

    @Test
    fun `a runCatching in a finally is discarded`() {
        val code =
            """
            fun handle() {
                try {
                    send()
                } finally {
                    runCatching { session.close() }
                }
            }
            """.trimIndent()
        assertEquals(listOf("kapkan:swallowed-failure"), lint(code).ids())
    }

    @Test
    fun `a runCatching last in a launch is discarded`() {
        // B-39's own shape: `launch` takes a `suspend () -> Unit` and answers with a Job that knows
        // nothing about the Result.
        val code =
            """
            fun report() {
                scope.launch {
                    runCatching { client.post(body) }
                }
            }
            """.trimIndent()
        // BOTH RULES ANSWER THIS ONE, and the snippet is where they meet: the Result is dropped on
        // the floor (this rule) and the cancellation became a value on the way (`cancellation-
        // swallowed`). Fixing one still owes the other.
        assertEquals(
            listOf("kapkan:cancellation-swallowed", "kapkan:swallowed-failure"),
            lint(code).ids().sorted(),
        )
    }

    @Test
    fun `a runCatching whose value is returned is not`() {
        val code =
            """
            fun handle(): Result<Unit> = runCatching { send() }
            """.trimIndent()
        assertEquals(emptyList<String>(), lint(code).ids())
    }

    @Test
    fun `a runCatching whose value is read is not`() {
        val code =
            """
            fun handle() {
                val outcome = runCatching { send() }
                report(outcome)
            }
            """.trimIndent()
        assertEquals(emptyList<String>(), lint(code).ids())
    }

    @Test
    fun `a runCatching collected by a lambda is not`() {
        // `map { runCatching { … } }` keeps every Result. The lambda's last expression is its value,
        // and only `launch` is exempt from that.
        val code =
            """
            fun handle(): List<Result<Unit>> = items.map { runCatching { send(it) } }
            """.trimIndent()
        assertEquals(emptyList<String>(), lint(code).ids())
    }

    @Test
    fun `an async lambda keeps its Result`() {
        val code =
            """
            fun handle() {
                scope.async {
                    runCatching { send() }
                }
            }
            """.trimIndent()
        // What this test says is that THIS rule is silent. `cancellation-swallowed` is not, and
        // correctly: `async`'s lambda suspends, so the `runCatching` in it swallows a cancellation
        // whatever happens to the Result afterwards.
        assertEquals(listOf("kapkan:cancellation-swallowed"), lint(code).ids())
    }

    @Test
    fun `a catch that never looks at what it caught is a defect`() {
        val code =
            """
            fun handle() {
                try {
                    send()
                } catch (e: Exception) {
                    retryLater()
                }
            }
            """.trimIndent()
        assertEquals(listOf("kapkan:swallowed-failure"), lint(code).ids())
    }

    @Test
    fun `an empty catch is a defect`() {
        val code =
            """
            fun handle() {
                try {
                    send()
                } catch (e: Throwable) {
                }
            }
            """.trimIndent()
        assertEquals(listOf("kapkan:swallowed-failure"), lint(code).ids())
    }

    @Test
    fun `a catch that logs what it caught is not`() {
        val code =
            """
            fun handle() {
                try {
                    send()
                } catch (e: Exception) {
                    log.warn("sending failed", e)
                }
            }
            """.trimIndent()
        assertEquals(emptyList<String>(), lint(code).ids())
    }

    @Test
    fun `a catch of a named type is a decision, not a swallow`() {
        val code =
            """
            fun handle() {
                try {
                    send()
                } catch (e: SerializationException) {
                    useDefault()
                }
            }
            """.trimIndent()
        assertEquals(emptyList<String>(), lint(code).ids())
    }

    @Test
    fun `a runCatching whose failure is logged is not swallowed`() {
        // THE SHAPE THAT MADE THIS RULE WRONG ON ITS FIRST CONSUMER. The `Result` is discarded — the
        // whole chain is a statement — and the failure is reported, which is correct code. Five of
        // the nine findings on shashki were this, against four real ones.
        val code =
            """
            fun handle() {
                runCatching { send() }.onFailure { log.warn("sending failed", it) }
            }
            """.trimIndent()
        assertEquals(emptyList<String>(), lint(code).ids())
    }

    @Test
    fun `a runCatching turned into a throw is not swallowed`() {
        val code =
            """
            fun handle() {
                runCatching { send() }.getOrElse { throw AssertionError("never sent") }
            }
            """.trimIndent()
        assertEquals(emptyList<String>(), lint(code).ids())
    }

    @Test
    fun `getOrNull does not read the failure`() {
        // `getOrNull` and `getOrDefault` answer "what is the value" and drop the exception on the
        // way, which is the shape the rule is about. They are not in the list, and this is the test
        // that says the list is a list and not "anything chained".
        val code =
            """
            fun handle() {
                runCatching { send() }.getOrNull()
            }
            """.trimIndent()
        assertEquals(listOf("kapkan:swallowed-failure"), lint(code).ids())
    }
}
