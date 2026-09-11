package io.github.youndie.sborka.internal

import io.github.youndie.sborka.internal.sizes.MethodSizesCallsFixture
import io.github.youndie.sborka.internal.sizes.MethodSizesChainFixture
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

    private val callsFixture =
        File(compiledFixtures, "io/github/youndie/sborka/internal/sizes/MethodSizesCallsFixture.class")

    private val chainFixture =
        File(compiledFixtures, "io/github/youndie/sborka/internal/sizes/MethodSizesChainFixture.class")

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

    @Test
    fun `a pattern built from an interpolated string is told apart from a constant one`() {
        val methods = MethodSizes.parse(callsFixture)!!

        val interpolated = methods.single { it.name == "interpolated" }
        assertEquals(1, interpolated.patternsCompiled)
        assertEquals(1, interpolated.patternsInterpolated, "the string is built at the call site")

        // The control, and it is the one that matters: a constant pattern must NOT be reported as
        // interpolated, or the report would tell every caller the thing it can do nothing about.
        val constant = methods.single { it.name == "perCall" }
        assertEquals(1, constant.patternsCompiled)
        assertEquals(0, constant.patternsInterpolated)
    }

    @Test
    fun `an eager chain is a finding and its sequence form is not`() {
        val methods = MethodSizes.parse(chainFixture)!!

        val eager = methods.single { it.name == "eagerChain" }
        assertTrue(
            eager.materialisations >= MethodSizes.CHAIN_FROM,
            "the eager chain materialised ${eager.materialisations}, which is not a finding",
        )
        // The two controls, and they are the point of the threshold: one operator is a method that
        // builds a collection, and a sequence builds one at the end however long the chain is.
        assertEquals(1, methods.single { it.name == "oneOperator" }.materialisations)
        assertEquals(0, methods.single { it.name == "lazyChain" }.materialisations)
        assertEquals(0, methods.single { it.name == "arithmetic" }.materialisations)
    }

    @Test
    fun `a string chain counts, which a collection-only reader would miss`() {
        val methods = MethodSizes.parse(chainFixture)!!
        val strings = methods.single { it.name == "stringChain" }

        // `reversed`, `chunked`, `joinToString`, `reversed` — four calls into `StringsKt` and
        // `CollectionsKt`, not one of them a collection operator a `filter`/`map` reader watches
        // for. This is the shape konekt's profile charged more than any other user method.
        assertTrue(
            strings.materialisations >= MethodSizes.CHAIN_FROM,
            "the string chain materialised ${strings.materialisations}",
        )
    }

    @Test
    fun `the materialisation count agrees with javap`() {
        val fromReader = MethodSizes.parse(chainFixture)!!.single { it.name == "eagerChain" }
        val body = bodyOf(javap(chainFixture), "eagerChain")

        // The same count out of the disassembly: a container the compiler instantiates for an
        // inlined operator, plus an operator that stayed a call. Counted by a different reader, on
        // the same bytes — which is what would catch a pool index read from the wrong place.
        val containers =
            body.lineSequence().count { line ->
                line.contains("new ") &&
                    listOf("java/util/ArrayList", "java/util/LinkedHashMap", "java/util/LinkedHashSet")
                        .any { line.contains("// class $it") }
            }
        val operators = body.lineSequence().count { it.contains("kotlin/collections/CollectionsKt.sortedWith:") }

        assertTrue(containers > 0, "javap found no containers at all — has the fixture changed?")
        assertEquals(containers + operators, fromReader.materialisations)
    }

    /** The lines of one method's `Code`, from its signature to the next member's. */
    private fun bodyOf(
        disassembly: String,
        method: String,
    ): String =
        disassembly
            .substringAfter(" $method(")
            .lineSequence()
            .takeWhile { !Regex("^ {2}\\S.*[;{]\\s*$").containsMatchIn(it) }
            .joinToString("\n")

    private fun javap(classFile: File): String {
        val out = StringWriter()
        val javap = ToolProvider.findFirst("javap").orElseThrow()
        PrintWriter(out).use { writer ->
            check(javap.run(writer, writer, "-c", "-p", classFile.path) == 0) { "javap failed: $out" }
        }
        return out.toString()
    }
}
