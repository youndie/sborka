package io.github.youndie.sborka.internal

import io.github.youndie.sborka.internal.sizes.MethodSizesFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.spi.ToolProvider

/**
 * The size reader, against BYTECODE THE COMPILER ACTUALLY PRODUCED — the same arrangement as
 * `JoinsTest`, and for the same reason: what a Kotlin function compiles to is the thing least worth
 * assuming.
 */
class MethodSizesTest {
    private val fixturePackage = "io/github/youndie/sborka/internal/sizes"

    private val compiledFixtures =
        File(
            MethodSizesFixture::class.java.protectionDomain.codeSource.location
                .toURI(),
        )

    private val fixtureDir = File(compiledFixtures, fixturePackage)

    private fun methodsOf(simpleName: String) =
        MethodSizes.parse(File(fixtureDir, "$simpleName.class"))
            ?: error("the reader refused $simpleName.class")

    @Test
    fun `a long body is measured and a short one is not a finding`() {
        val methods = methodsOf("MethodSizesFixture")

        val big = methods.single { it.name == "big" }
        val small = methods.single { it.name == "small" }

        // The number is the compiler's, so the assertion is the threshold rather than a literal:
        // pinning 431 bytes here would fail on the next Kotlin release for no defect.
        assertTrue(
            big.bytes > MethodSizes.FREQ_INLINE_SIZE.bytes,
            "the fixture's big body compiled to ${big.bytes} bytes, which no longer clears " +
                "${MethodSizes.FREQ_INLINE_SIZE.flag} (${MethodSizes.FREQ_INLINE_SIZE.bytes}) — " +
                "the fixture needs to grow, the reader is fine",
        )
        assertTrue(small.bytes < MethodSizes.FREQ_INLINE_SIZE.bytes, "small() is ${small.bytes} bytes")

        // EVERY threshold under the body, not just the largest: a 431-byte method is over
        // FreqInlineSize and over MaxInlineSize both, and the report says which ones so that a
        // reader is not left to work out where 431 sits.
        assertEquals(
            listOf(MethodSizes.FREQ_INLINE_SIZE, MethodSizes.MAX_INLINE_SIZE),
            big.crossed,
        )
        assertEquals(emptyList<MethodSizes.Threshold>(), small.crossed)
    }

    @Test
    fun `a method without a body is read and reported as nothing`() {
        val methods = methodsOf("MethodSizesFixturePort")

        // The interface method has no `Code` attribute; the reader must walk past it rather than
        // call it zero bytes, which would put "the smallest method in the build" on every report.
        assertTrue(methods.none { it.name == "bodyless" }, "got $methods")
    }

    @Test
    fun `the scan finds the long body and counts what it read`() {
        val report = MethodSizes.scan(listOf(compiledFixtures))

        assertTrue(report.classesRead > 0, "read no classes at all")
        assertTrue(report.methodsRead > report.findings.size, "every method was a finding")

        val finding = report.findings.firstOrNull { it.name == "big" }
        assertNotNull(finding, "the long body is missing from ${report.findings}")
        assertEquals("io.github.youndie.sborka.internal.sizes.MethodSizesFixture", finding!!.className)
    }

    @Test
    fun `the reader agrees with javap on the same method`() {
        // A SECOND READER, and that is the point of the test. Everything else here checks this
        // parser against itself: that a long body is long and a short one is short. javap walks the
        // same file with the JDK's own class reader, so an off-by-one in the attribute skipping —
        // the mistake this kind of code actually makes — shows up as a disagreement rather than as a
        // plausible number.
        val classFile = File(fixtureDir, "MethodSizesFixture.class")
        val disassembly = javap(classFile)

        val body =
            disassembly
                .substringAfter(" big(")
                .lineSequence()
                .map { it.trim() }
                .takeWhile { !it.startsWith("public") && !it.startsWith("private") }
                .filter { it.matches(Regex("^\\d+: .*")) }
                .toList()

        val last = body.last()
        val lastOffset = last.substringBefore(':').toInt()

        // The `+ 1` is the width of that last instruction, and it is only right because the
        // instruction is a one-byte return — asserted rather than assumed, so that a fixture edit
        // that ends the method differently fails here instead of shifting the expected size.
        assertTrue(last.endsWith("areturn"), "the fixture's big() no longer ends in areturn: $last")

        val mine = methodsOf("MethodSizesFixture").single { it.name == "big" }
        assertEquals(lastOffset + 1, mine.bytes, "javap and the reader disagree about big()")
    }

    @Test
    fun `a class compiled into two outputs is one finding that names both`(
        @TempDir tmp: File,
    ) {
        // THE SHAPE A MULTIPLATFORM BUILD PRODUCES: one class, two compilations, two directories.
        // The copies are identical here, which is the ordinary case — shashki's `socketUrl` arrived
        // twice from jvm and android and the bodies agreed. What this pins is that the reader
        // reports the method once and says where the copies were.
        val source = File(fixtureDir, "MethodSizesChainFixture.class")
        listOf("kotlin/jvm/main", "kotlin/androidDebug").forEach { output ->
            val target = File(tmp, "$output/$fixturePackage").apply { mkdirs() }
            source.copyTo(File(target, source.name), overwrite = true)
        }

        val report = MethodSizes.scan(listOf(tmp))

        val chain = report.chains.single { it.name == "eagerChain" }
        assertEquals(listOf("kotlin/androidDebug", "kotlin/jvm/main"), chain.outputs.sorted())
        assertEquals(false, chain.divergent, "identical copies are not a divergence")

        // The counts are of DISTINCT classes and methods, and the copies are counted separately so
        // that "one finding" and "two class files" are both visible.
        assertEquals(1, report.classesRead)
        assertTrue(report.duplicateCopies > 0, "the second copy was not counted as one")
        assertEquals(
            MethodSizes.parse(source)!!.size,
            report.methodsRead,
            "the same class read twice reported more methods than it has",
        )
    }

    @Test
    fun `a body that runs per composition or per frame is marked by its signature`() {
        // A pure question about a descriptor, so the test asks it directly rather than dragging
        // Compose onto this module's classpath to compile a fixture against.
        fun method(descriptor: String) =
            MethodSizes.Method(
                className = "ui.ScreenKt",
                name = "Screen",
                descriptor = descriptor,
                bytes = 0,
            )

        assertTrue(
            method("(Ljava/util/List;Landroidx/compose/runtime/Composer;I)V").repeats,
            "a Composer parameter is what the Compose compiler adds to a @Composable",
        )
        assertTrue(
            method("(Landroidx/compose/ui/graphics/drawscope/DrawScope;Ljava/util/List;)V").repeats,
            "a DrawScope means the body draws, which happens per frame",
        )
        assertEquals(false, method("(Ljava/util/List;I)Ljava/lang/String;").repeats)
    }

    @Test
    fun `a finding from a test compilation is marked, and one compiled into both is not`(
        @TempDir tmp: File,
    ) {
        // WHAT THE GATE MAY JUDGE. A pattern rebuilt per call in a test costs a slow suite and
        // nothing else; the report prints it and the gate has to leave it alone. The rule is the
        // output directory, shared with `Joins`, because a source directory can feed several
        // compilations.
        val source = File(fixtureDir, "MethodSizesChainFixture.class")

        fun scanOf(vararg outputs: String): MethodSizes.Method {
            val root = File(tmp, outputs.joinToString("-") { it.replace('/', '_') }).apply { mkdirs() }
            outputs.forEach { output ->
                val target = File(root, "$output/$fixturePackage").apply { mkdirs() }
                source.copyTo(File(target, source.name), overwrite = true)
            }
            return MethodSizes.scan(listOf(root)).chains.single { it.name == "eagerChain" }
        }

        assertTrue(scanOf("kotlin/test").fromTestOutput, "kotlin/test is a test compilation")
        assertTrue(scanOf("kotlin/jvmTest").fromTestOutput, "so is jvmTest")
        assertEquals(false, scanOf("kotlin/jvm/main").fromTestOutput)

        // A class compiled into both ships, so one shipping copy takes it out of the test half.
        assertEquals(false, scanOf("kotlin/jvm/main", "kotlin/test").fromTestOutput)
    }

    private fun javap(classFile: File): String {
        val out = StringWriter()
        val javap =
            ToolProvider.findFirst("javap").orElseThrow {
                IllegalStateException("no javap in this JDK, so the cross-check cannot run")
            }
        PrintWriter(out).use { writer ->
            val status = javap.run(writer, writer, "-c", "-p", classFile.path)
            check(status == 0) { "javap failed: $out" }
        }
        return out.toString()
    }

    @Test
    fun `the thresholds are ordered from the largest down`() {
        // The report names the largest threshold a body crosses first, which is only true while the
        // table is sorted — and the table is data typed by hand from PrintFlagsFinal.
        assertEquals(MethodSizes.ALL.sortedByDescending { it.bytes }, MethodSizes.ALL)
    }
}
