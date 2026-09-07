package io.github.youndie.sborka.internal

import io.github.youndie.sborka.internal.sizes.MethodSizesCallsFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.spi.ToolProvider

/**
 * The instruction walk, checked where it can actually be wrong.
 *
 * A width table is right or wrong per opcode, and the opcodes that break it are the three variable
 * ones. So the test does not walk a hand-picked method: it walks EVERY method this module compiled —
 * `when` over strings and enums included, which is where `tableswitch` and `lookupswitch` come
 * from — and requires the walk to land exactly on the end of each body. A wrong width lands
 * somewhere else, and the walker refuses rather than reporting calls it invented.
 */
class BytecodeTest {
    private val compiledFixtures =
        File(
            MethodSizesCallsFixture::class.java.protectionDomain.codeSource.location
                .toURI(),
        )

    private val mainClasses = File(compiledFixtures.parentFile, "main")

    @Test
    fun `every compiled body in this module walks to its exact end`() {
        val report = MethodSizes.scan(listOf(compiledFixtures, mainClasses))

        assertTrue(report.classesRead > 10, "only ${report.classesRead} classes — is the output there?")
        assertTrue(report.methodsRead > 50, "only ${report.methodsRead} method bodies")
        assertEquals(emptyList<String>(), report.unwalked, "the walk did not land on the end of these")
    }

    @Test
    fun `the assertion count agrees with javap`() {
        val classFile = File(compiledFixtures, "io/github/youndie/sborka/internal/sizes/MethodSizesCallsFixture.class")
        val checked = MethodSizes.parse(classFile)!!.single { it.name == "checked" }

        // The same count, read out of the disassembly by a different reader: `javap` names every
        // call site, and the parameter checks are the `Intrinsics.check…` among them.
        val fromJavap =
            javap(classFile)
                .substringAfter(" checked(")
                .lineSequence()
                .takeWhile { !it.contains("public ") || it.contains("checked(") }
                .count { it.contains("Intrinsics.check") }

        assertTrue(fromJavap > 0, "javap found no parameter checks at all — has the fixture changed?")
        assertEquals(fromJavap, checked.assertions)
    }

    @Test
    fun `a pattern built per call is a finding and one built in clinit is not`() {
        val classFile = File(compiledFixtures, "io/github/youndie/sborka/internal/sizes/MethodSizesCallsFixture.class")
        val methods = MethodSizes.parse(classFile)!!

        assertEquals(1, methods.single { it.name == "perCall" }.patternsCompiled)
        assertEquals(0, methods.single { it.name == "viaShared" }.patternsCompiled)

        // The companion's initialiser builds the shared one. Wherever the compiler put it, it is not
        // counted: `<clinit>` is where a pattern is supposed to be built.
        assertTrue(
            methods.filter { it.name == "<clinit>" }.all { it.patternsCompiled == 0 },
            "a <clinit> was counted: ${methods.filter { it.name == "<clinit>" }}",
        )
    }

    private fun javap(classFile: File): String {
        val out = StringWriter()
        val javap = ToolProvider.findFirst("javap").orElseThrow()
        PrintWriter(out).use { writer ->
            check(javap.run(writer, writer, "-c", "-p", classFile.path) == 0) { "javap failed: $out" }
        }
        return out.toString()
    }
}
