package io.github.youndie.sborka.kapkan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CancellationSwallowedRuleTest {
    @Test
    fun `a runCatching in a suspend fun is flagged`() {
        // mani's `UseCase.withTry`, reduced: the Result is read impeccably — which is why
        // `swallowed-failure` says nothing about it.
        val code =
            """
            suspend fun load(): Result<Data> = runCatching { client.get(url) }
            """.trimIndent()
        assertEquals(listOf("kapkan:cancellation-swallowed"), lint(code).ids())
    }

    @Test
    fun `a runCatching in a plain fun is not`() {
        val code =
            """
            fun parse(text: String): Result<Int> = runCatching { text.toInt() }
            """.trimIndent()
        assertEquals(emptyList<String>(), lint(code).ids())
    }

    @Test
    fun `a runCatching inside a launch is flagged`() {
        // Three of mani's four sites lived here: the enclosing function is not `suspend`, the block
        // is. Nothing but the builder's name says so.
        val code =
            """
            fun refresh() {
                scope.launch {
                    val outcome = runCatching { repository.load() }
                    render(outcome)
                }
            }
            """.trimIndent()
        assertEquals(listOf("kapkan:cancellation-swallowed"), lint(code).ids())
    }

    @Test
    fun `a runCatching inside a lambda that is not a builder is not`() {
        // `handler { … }` is a name this rule has never heard of, and inventing a suspending block
        // out of one would invent findings out of nothing.
        val code =
            """
            fun register() {
                handler {
                    runCatching { send() }.getOrNull()
                }
            }
            """.trimIndent()
        assertEquals(emptyList<String>(), lint(code).ids())
    }

    @Test
    fun `a runCatching inside an inline lambda of a suspend fun is flagged`() {
        // `map` is inline, so its lambda suspends with the function around it. The walk goes through
        // every lambda it cannot name for exactly this reason.
        val code =
            """
            suspend fun loadAll(ids: List<Id>): List<Result<Data>> =
                ids.map { runCatching { client.get(it) } }
            """.trimIndent()
        assertEquals(listOf("kapkan:cancellation-swallowed"), lint(code).ids())
    }

    @Test
    fun `a runCatching in a plain fun nested inside a suspend fun is not`() {
        // The walk stops at the FIRST function it meets, not at the outermost one.
        val code =
            """
            suspend fun outer() {
                fun inner(): Result<Int> = runCatching { compute() }
                use(inner())
            }
            """.trimIndent()
        assertEquals(emptyList<String>(), lint(code).ids())
    }

    @Test
    fun `a catch of Exception in a suspend fun is flagged`() {
        val code =
            """
            suspend fun verify(token: String): Principal? =
                try {
                    jwt.decode(token)
                } catch (e: Exception) {
                    logger.warn("bad token", e)
                    null
                }
            """.trimIndent()
        assertEquals(listOf("kapkan:cancellation-swallowed"), lint(code).ids())
    }

    @Test
    fun `a catch of Throwable in a suspend fun is flagged`() {
        val code =
            """
            suspend fun sweep() {
                try {
                    store.sweep()
                } catch (t: Throwable) {
                    report(t)
                }
            }
            """.trimIndent()
        assertEquals(listOf("kapkan:cancellation-swallowed"), lint(code).ids())
    }

    @Test
    fun `a catch of a specific type is not`() {
        // A `catch` that names what it expects is answerable for it, and no name like this one covers
        // CancellationException.
        val code =
            """
            suspend fun read(): Config? =
                try {
                    decode(file.readText())
                } catch (e: SerializationException) {
                    logger.warn("unreadable config", e)
                    null
                }
            """.trimIndent()
        assertEquals(emptyList<String>(), lint(code).ids())
    }

    @Test
    fun `a catch of Exception in a plain fun is not`() {
        val code =
            """
            fun read(): Config? =
                try {
                    decode(file.readText())
                } catch (e: Exception) {
                    logger.warn("unreadable config", e)
                    null
                }
            """.trimIndent()
        assertEquals(emptyList<String>(), lint(code).ids())
    }

    @Test
    fun `a rethrowing CancellationException clause ahead of it clears it`() {
        val code =
            """
            suspend fun verify(token: String): Principal? =
                try {
                    jwt.decode(token)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("bad token", e)
                    null
                }
            """.trimIndent()
        assertEquals(emptyList<String>(), lint(code).ids())
    }

    @Test
    fun `a CancellationException clause that does not rethrow does not clear it`() {
        // The clause looks deliberate and swallows the cancellation anyway. That is the defect with
        // an alibi, and it is worth one finding.
        val code =
            """
            suspend fun verify(token: String): Principal? =
                try {
                    jwt.decode(token)
                } catch (e: CancellationException) {
                    logger.warn("cancelled", e)
                    null
                } catch (e: Exception) {
                    logger.warn("bad token", e)
                    null
                }
            """.trimIndent()
        assertEquals(listOf("kapkan:cancellation-swallowed"), lint(code).ids())
    }

    @Test
    fun `a CancellationException clause after it does not clear it`() {
        // Unreachable: the broad clause already caught it. Order is the whole of this rule's second
        // half, so the test says so out loud.
        val code =
            """
            suspend fun verify(token: String): Principal? =
                try {
                    jwt.decode(token)
                } catch (e: Exception) {
                    logger.warn("bad token", e)
                    null
                } catch (e: CancellationException) {
                    throw e
                }
            """.trimIndent()
        assertEquals(listOf("kapkan:cancellation-swallowed"), lint(code).ids())
    }

    @Test
    fun `a catch that tests for the type itself and rethrows is not flagged`() {
        val code =
            """
            suspend fun verify(token: String): Principal? =
                try {
                    jwt.decode(token)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
            """.trimIndent()
        assertEquals(emptyList<String>(), lint(code).ids())
    }

    @Test
    fun `a catch that calls ensureActive is not flagged`() {
        // kotlinx.coroutines documents this one: `ensureActive()` rethrows the context's own
        // cancellation, so the failure that is left is a real failure.
        val code =
            """
            suspend fun verify(token: String): Principal? =
                try {
                    jwt.decode(token)
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    logger.warn("bad token", e)
                    null
                }
            """.trimIndent()
        assertEquals(emptyList<String>(), lint(code).ids())
    }

    @Test
    fun `a qualified CancellationException is read by its simple name`() {
        val code =
            """
            suspend fun verify(token: String): Principal? =
                try {
                    jwt.decode(token)
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: java.lang.Exception) {
                    logger.warn("bad token", e)
                    null
                }
            """.trimIndent()
        assertEquals(emptyList<String>(), lint(code).ids())
    }

    @Test
    fun `a suspendRunCatching is not a runCatching`() {
        // The remedy the rule's own message names. It has to be invisible to the rule, or adopting it
        // would leave the finding behind.
        val code =
            """
            suspend fun load(): Result<Data> = suspendRunCatching { client.get(url) }
            """.trimIndent()
        assertEquals(emptyList<String>(), lint(code).ids())
    }

    @Test
    fun `the two rules are separate findings on the same line`() {
        // The pair, side by side, on the shape that answers both: the Result is discarded AND the
        // cancellation becomes a value. Two ids, and a repository fixing one still owes the other.
        val code =
            """
            suspend fun refresh() {
                runCatching { client.get(url) }
            }
            """.trimIndent()
        assertEquals(
            listOf("kapkan:cancellation-swallowed", "kapkan:swallowed-failure"),
            lint(code).ids().sorted(),
        )
    }

    @Test
    fun `a catch of Exception in a property accessor is not`() {
        // No accessor is `suspend`, so the walk answers before it reaches the class around it.
        val code =
            """
            val config: Config?
                get() =
                    try {
                        decode(file.readText())
                    } catch (e: Exception) {
                        logger.warn("unreadable config", e)
                        null
                    }
            """.trimIndent()
        assertEquals(emptyList<String>(), lint(code).ids())
    }

    @Test
    fun `a catch that rethrows what it caught is not flagged`() {
        // s3kn's `S3Client.uploadMultipart`: compensate, then hand the failure on untouched. A
        // cancellation leaves through the same `throw`, so nothing was turned into a value.
        val code =
            """
            suspend fun upload(): Etag =
                try {
                    complete(parts())
                } catch (failure: Throwable) {
                    abort()
                    throw failure
                }
            """.trimIndent()
        assertEquals(emptyList<String>(), lint(code).ids())
    }

    @Test
    fun `a catch that rethrows a wrapper is flagged`() {
        // `throw Wrapped(e)` is how a cancellation stops being one: the caller sees a domain failure
        // and the coroutine that was cancelled is not.
        val code =
            """
            suspend fun upload(): Etag =
                try {
                    complete(parts())
                } catch (failure: Throwable) {
                    throw UploadFailed(failure)
                }
            """.trimIndent()
        assertEquals(listOf("kapkan:cancellation-swallowed"), lint(code).ids())
    }

    @Test
    fun `nothing inside withContext NonCancellable is flagged`() {
        // The language's own way of saying this block cannot be cancelled — so there is no
        // cancellation here to turn into a value. Both shapes, because both appear inside one.
        val code =
            """
            suspend fun abortQuietly() {
                withContext(NonCancellable) {
                    runCatching { abortMultipartUpload(upload) }
                    try {
                        session.close()
                    } catch (e: Throwable) {
                        logger.warn("close failed", e)
                    }
                }
            }
            """.trimIndent()
        // `swallowed-failure` still has its say — the Result is dropped on the floor, which is s3kn's
        // real line and its real suppression. THIS rule is the one that has nothing to say here.
        assertEquals(listOf("kapkan:swallowed-failure"), lint(code).ids())
    }

    @Test
    fun `a plain withContext is still a suspending block`() {
        val code =
            """
            suspend fun load(): Result<Data> =
                withContext(dispatcher) {
                    runCatching { client.get(url) }
                }
            """.trimIndent()
        assertEquals(listOf("kapkan:cancellation-swallowed"), lint(code).ids())
    }
}
