package io.github.youndie.sborka.kapkan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SignalHandlerInKotlinRuleTest {
    private val rule = "kapkan:signal-handler-in-kotlin"

    // A signal handler only exists in a native source set: `platform.posix` resolves nowhere else.
    private val native = "/repo/module/src/linuxX64Main/kotlin/Signals.kt"

    private fun lintNative(code: String) = lint(code.trimIndent() + "\n", path = native)

    @Test
    fun `a staticCFunction handed straight to signal is a handler in Kotlin`() {
        // kore's macOS form, at 58011c7: one `signal` per line, the lambda written in the call.
        val errors =
            lintNative(
                """
                import kotlinx.cinterop.staticCFunction
                import platform.posix.SIGINT
                import platform.posix.SIGTERM
                import platform.posix.signal

                fun install() {
                    signal(SIGTERM, staticCFunction<Int, Unit> { raised.compareAndSet(0, SIGTERM) })
                    signal(SIGINT, staticCFunction<Int, Unit> { raised.compareAndSet(0, SIGINT) })
                }
                """,
            )
        assertEquals(listOf(rule, rule), errors.ids())
        assertEquals(
            listOf(7 to 21, 8 to 20),
            errors.map { it.line to it.col },
            "each finding points at the staticCFunction",
        )
    }

    @Test
    fun `the message names the remedy and the two working examples`() {
        val errors =
            lintNative(
                """
                import kotlinx.cinterop.staticCFunction
                import platform.posix.SIGTERM
                import platform.posix.signal

                fun install() {
                    signal(SIGTERM, staticCFunction<Int, Unit> { })
                }
                """,
            )
        val detail = errors.single().detail
        listOf("C", ".def", "---", "atomic store", "koreSignal.def", "sampler.def").forEach {
            assertTrue(detail.contains(it), "the message should name `$it`: $detail")
        }
    }

    @Test
    fun `a val initialised with staticCFunction and passed to signal is the same handler`() {
        // metrik's CLI form, at d7031e2: the handler is named first and installed twice.
        val errors =
            lintNative(
                """
                import kotlinx.cinterop.staticCFunction
                import platform.posix.SIGINT
                import platform.posix.SIGTERM
                import platform.posix.exit
                import platform.posix.signal

                private fun installSignalHandlers() {
                    val handler =
                        staticCFunction<Int, Unit> { code ->
                            leaveFullScreen()
                            exit(128 + code)
                        }

                    signal(SIGINT, handler)
                    signal(SIGTERM, handler)
                }
                """,
            )
        assertEquals(listOf(rule, rule), errors.ids())
        assertEquals(listOf(14, 15), errors.map { it.line }, "the finding is the installation, not the declaration")
    }

    @Test
    fun `a top-level val and a function reference are the same handler`() {
        val errors =
            lintNative(
                """
                import kotlinx.cinterop.staticCFunction
                import platform.posix.*

                private fun onSignal(code: Int) {}

                private val handler = staticCFunction(::onSignal)

                fun install() {
                    signal(SIGTERM, handler)
                    signal(SIGINT, kotlinx.cinterop.staticCFunction(::onSignal))
                }
                """,
            )
        assertEquals(listOf(rule, rule), errors.ids())
    }

    @Test
    fun `a staticCFunction assigned to sa_handler or sa_sigaction is a handler in Kotlin`() {
        // The two field paths cinterop gives `struct sigaction`: glibc's `__sigaction_handler` union
        // on Linux, `__sigaction_u` with `__sa_`-prefixed members on Apple.
        val errors =
            lintNative(
                """
                import kotlinx.cinterop.alloc
                import kotlinx.cinterop.memScoped
                import kotlinx.cinterop.ptr
                import kotlinx.cinterop.staticCFunction
                import platform.posix.SIGTERM
                import platform.posix.sigaction

                private val onInfo = staticCFunction<Int, CPointer<siginfo_t>?, COpaquePointer?, Unit> { _, _, _ -> }

                fun install() = memScoped {
                    val linux = alloc<sigaction>()
                    linux.__sigaction_handler.sa_handler = staticCFunction<Int, Unit> { }
                    val apple = alloc<sigaction> { __sigaction_u.__sa_sigaction = onInfo }
                    val bare = alloc<sigaction> { sa_handler = staticCFunction<Int, Unit> { } }
                    sigaction(SIGTERM, linux.ptr, null)
                }
                """,
            )
        assertEquals(listOf(rule, rule, rule), errors.ids())
        assertEquals(listOf(12, 13, 14), errors.map { it.line })
    }

    @Test
    fun `a staticCFunction handed to something other than signal is not a handler`() {
        // qsort's comparator, a curl callback: a C-to-Kotlin bridge called on a thread that is
        // already in Kotlin, which is what staticCFunction is for.
        val errors =
            lintNative(
                """
                import kotlinx.cinterop.staticCFunction
                import platform.posix.*

                private val compare = staticCFunction<COpaquePointer?, COpaquePointer?, Int> { _, _ -> 0 }

                fun sort(base: COpaquePointer, n: ULong) {
                    qsort(base, n, 4u, compare)
                    signal(SIGPIPE, SIG_IGN)
                }
                """,
            )
        assertEquals(emptyList<String>(), errors.ids())
    }

    @Test
    fun `a signal from another package is not flagged`() {
        // The gate: without `platform.posix.signal`, `platform.posix.sigaction` or `platform.posix.*`
        // among the imports, `signal` is somebody else's function — a coroutine primitive, a Compose
        // state — and the file is not read at all.
        val errors =
            lintNative(
                """
                import kotlinx.cinterop.staticCFunction
                import io.example.events.signal

                private val handler = staticCFunction<Int, Unit> { }

                fun install() {
                    signal(SIGTERM, staticCFunction<Int, Unit> { })
                    signal(SIGINT, handler)
                    options.sa_handler = staticCFunction<Int, Unit> { }
                }
                """,
            )
        assertEquals(emptyList<String>(), errors.ids())
    }

    @Test
    fun `somebody else's signal method in a posix file is not flagged`() {
        val errors =
            lintNative(
                """
                import kotlinx.cinterop.staticCFunction
                import platform.posix.signal

                fun wake() {
                    condition.signal(staticCFunction<Int, Unit> { })
                }
                """,
            )
        assertEquals(emptyList<String>(), errors.ids())
    }

    @Test
    fun `an aliased import is followed under its alias`() {
        val errors =
            lintNative(
                """
                import kotlinx.cinterop.staticCFunction
                import platform.posix.signal as posixSignal

                fun install() {
                    posixSignal(SIGTERM, staticCFunction<Int, Unit> { })
                    signal(SIGTERM, staticCFunction<Int, Unit> { })
                }
                """,
            )
        assertEquals(listOf(rule), errors.ids())
        assertEquals(5, errors.single().line)
    }

    @Test
    fun `a suppression with a reason silences it`() {
        // kore's macOS handler, the one that is deliberate: Apple cinterop is not built on kore's
        // Linux release host, and its KDoc already says it is exposed to B-64.
        val suppression =
            """
            @Suppress(
                "ktlint:kapkan:signal-handler-in-kotlin",
                "macOS is a development target and Apple cinterop is not built on the Linux release host",
            )
            """.trimIndent()

        fun code(annotation: String) =
            """
            |import kotlinx.cinterop.staticCFunction
            |import platform.posix.SIGTERM
            |import platform.posix.signal
            |
            |$annotation
            |fun install() {
            |    signal(SIGTERM, staticCFunction<Int, Unit> { raised.compareAndSet(0, SIGTERM) })
            |}
            """.trimMargin() + "\n"
        // THE CONTROL: the same file without the annotation is a finding, so the silence below is the
        // suppression's and not the rule's.
        assertEquals(listOf(rule), lint(code(""), path = native).ids())
        assertEquals(emptyList<String>(), lint(code(suppression), path = native).ids())
    }

    @Test
    fun `a suppression without a reason silences the handler and fails on its own`() {
        val errors =
            lintNative(
                """
                import kotlinx.cinterop.staticCFunction
                import platform.posix.SIGTERM
                import platform.posix.signal

                @Suppress("ktlint:kapkan:signal-handler-in-kotlin")
                fun install() {
                    signal(SIGTERM, staticCFunction<Int, Unit> { })
                }
                """,
            )
        assertEquals(listOf("kapkan:suppression-needs-a-reason"), errors.ids())
    }
}
