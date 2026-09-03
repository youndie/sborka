package ru.workinprogress.sborka.kapkan

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
        assertEquals(listOf("kapkan:swallowed-failure"), lint(code).ids())
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
        assertEquals(emptyList<String>(), lint(code).ids())
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
}
