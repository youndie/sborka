package io.github.youndie.sborka.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The comparison, tested on its own.
 *
 * Everything else in this repository is verified by applying it to a real build; this is the one piece
 * with logic that a build cannot easily be made to exercise from both sides — a shortfall has to be
 * arranged, and arranging one in the stand means committing a test that never runs.
 */
class DeclaredTestsTest {
    @Test
    fun `a class whose results are missing is a shortfall`() {
        val shortfalls =
            DeclaredTests.shortfalls(
                declared = mapOf("FooTest" to 2),
                reported = mapOf("pkg.BarTest" to 1),
            )
        assertEquals(listOf("FooTest declares 2 @Test and reported nothing at all"), shortfalls)
    }

    @Test
    fun `fewer reported than declared is a shortfall`() {
        val shortfalls =
            DeclaredTests.shortfalls(
                declared = mapOf("FooTest" to 3),
                reported = mapOf("pkg.FooTest" to 2),
            )
        assertEquals(listOf("pkg.FooTest declares 3 @Test and JUnit ran 2"), shortfalls)
    }

    @Test
    fun `more reported than declared is not`() {
        // A `@TestFactory` produces dynamic cases, and a generated screenshot fixture is one. Only a
        // shortfall is a defect.
        assertTrue(DeclaredTests.shortfalls(mapOf("FooTest" to 1), mapOf("pkg.FooTest" to 9)).isEmpty())
    }

    @Test
    fun `a multiplatform suite name carries its target and still matches`() {
        // A multiplatform test task names its suite `FooTest[jvm]`. Without stripping that, every
        // commonTest class in every multiplatform module reports as never having run — a guard that
        // cries wolf on a whole module is one that gets deleted.
        assertTrue(DeclaredTests.shortfalls(mapOf("FooTest" to 1), mapOf("pkg.FooTest[jvm]" to 1)).isEmpty())
    }

    @Test
    fun `only classes this task compiled are counted`(
        @TempDir tmp: File,
    ) {
        // A multiplatform module has ONE `src` tree and several test tasks over it, so scanning sources
        // alone would have `jvmTest` demand that an iosTest class appear in its results — a failure
        // with nothing wrong behind it.
        val src = File(tmp, "src/commonTest/kotlin/pkg").apply { mkdirs() }
        File(src, "CompiledTest.kt").writeText("package pkg\n\nclass CompiledTest {\n    @Test\n    fun a() {}\n}\n")
        File(src, "OtherTest.kt").writeText("package pkg\n\nclass OtherTest {\n    @Test\n    fun a() {}\n}\n")

        val classes = File(tmp, "classes").apply { mkdirs() }
        File(classes, "CompiledTest.class").writeText("")

        val declared = DeclaredTests.declaredIn(listOf(File(tmp, "src")), listOf(classes))
        assertEquals(mapOf("CompiledTest" to 1), declared.counts)
    }

    @Test
    fun `an excluded class is taken out of what is expected`(
        @TempDir tmp: File,
    ) {
        // Matched against the FULLY QUALIFIED name, because that is what Gradle's patterns are about.
        // Taking the tail after the last dot instead turns `pkg.*` into `*`, which matches every class
        // in the module and silently switches the whole check off.
        val src = File(tmp, "src/test/kotlin/pkg").apply { mkdirs() }
        File(src, "SkippedTest.kt").writeText("package pkg\n\nclass SkippedTest {\n    @Test\n    fun a() {}\n}\n")
        val classes = File(tmp, "classes").apply { mkdirs() }
        File(classes, "SkippedTest.class").writeText("")

        val declared = DeclaredTests.declaredIn(listOf(File(tmp, "src")), listOf(classes), excluded = setOf("pkg.*"))
        assertTrue(declared.counts.isEmpty())
    }

    @Test
    fun `a same-named class in another source set is not read`(
        @TempDir tmp: File,
    ) {
        // THE RED BUILD THIS WAS WRITTEN FOR. `jvmTest` and `nativeTest` each hold a
        // `CreateFileSystemTest`, same package, two tests and three. No compilation sees both, so it is
        // legal — and read from the whole `src/` the two files shared one name, the map kept whichever
        // the walk reached last, and `jvmTest` was asked for three on runners whose filesystem listed
        // `nativeTest` second.
        val jvmTest = sameNamedTests(tmp)

        val declared =
            DeclaredTests.declaredIn(
                sourceDirs = listOf(jvmTest, File(tmp, "src/commonTest/kotlin")),
                testClassesDirs = listOf(File(tmp, "classes")),
            )
        assertEquals(mapOf("CreateFileSystemTest" to 2), declared.counts)
        assertTrue(declared.ambiguous.isEmpty())
    }

    @Test
    fun `a name two files declare is set aside rather than decided by the walk`(
        @TempDir tmp: File,
    ) {
        // The fallback, for a task no Kotlin compilation can be matched to, still reads all of `src/`.
        // There the same two files cannot be told apart by the results — a multiplatform suite is
        // named `CreateFileSystemTest[jvm]`, without its package — so neither is checked and both are
        // named, whatever order the directories come in.
        sameNamedTests(tmp)

        val declared = DeclaredTests.declaredIn(listOf(File(tmp, "src")), listOf(File(tmp, "classes")))
        assertTrue(declared.counts.isEmpty())
        assertEquals(
            listOf(
                "src/jvmTest/kotlin/pkg/CreateFileSystemTest.kt",
                "src/nativeTest/kotlin/pkg/CreateFileSystemTest.kt",
            ),
            declared.ambiguous.getValue("CreateFileSystemTest").map { it.relativeTo(tmp).invariantSeparatorsPath },
        )
    }

    @Test
    fun `an exclusion matches the package the file declares`(
        @TempDir tmp: File,
    ) {
        // Not the directory it sits in: Kotlin does not require the two to agree, and a qualified name
        // is made of the declared one.
        val src = File(tmp, "src/test/kotlin").apply { mkdirs() }
        File(src, "FlatTest.kt").writeText("package pkg.deep\n\nclass FlatTest {\n    @Test\n    fun a() {}\n}\n")
        val classes = File(tmp, "classes").apply { mkdirs() }
        File(classes, "FlatTest.class").writeText("")

        val declared = DeclaredTests.declaredIn(listOf(src), listOf(classes), excluded = setOf("pkg.deep.*"))
        assertTrue(declared.counts.isEmpty())
    }

    // katcher's `client` as it was on the red runs: the JVM file with two tests, the native one with
    // three, one compiled class between them. Returns the JVM test source directory.
    private fun sameNamedTests(tmp: File): File {
        fun write(
            sourceSet: String,
            tests: Int,
        ): File {
            val root = File(tmp, "src/$sourceSet/kotlin")
            val body = (1..tests).joinToString("\n") { "    @Test\n    fun t$it() {}\n" }
            File(root, "pkg").mkdirs()
            File(root, "pkg/CreateFileSystemTest.kt").writeText("package pkg\n\nclass CreateFileSystemTest {\n$body}\n")
            return root
        }

        File(tmp, "src/commonTest/kotlin").mkdirs()
        File(tmp, "classes/pkg").mkdirs()
        File(tmp, "classes/pkg/CreateFileSystemTest.class").writeText("")
        write("nativeTest", tests = 3)
        return write("jvmTest", tests = 2)
    }

    @Test
    fun `a results directory that was never written reports nothing`(
        @TempDir tmp: File,
    ) {
        assertTrue(DeclaredTests.reportedIn(File(tmp, "never-created")).isEmpty())
    }

    @Test
    fun `a filter that cannot be read counts as filtered`() {
        // NOT SILENT WHEN IT CANNOT TELL. A run whose filter is unknown is treated as filtered, because
        // the alternative is condemning every class in the module on every `--tests` run.
        val patterns = DeclaredTests.commandLinePatterns(filter = "not a filter at all")
        assertTrue(patterns.isNotEmpty(), "an unreadable filter must not read as an empty filter")
    }

    @Test
    fun `two classes in one file are counted apart`(
        @TempDir tmp: File,
    ) {
        // A file holding two test classes used to report all of its annotations against the one the
        // file is named after, and the other class's runs counted against nothing — so the check
        // demanded twelve from a class that has nine, and failed on correct code.
        val src = File(tmp, "src/commonTest/kotlin/pkg").apply { mkdirs() }
        File(src, "FirstTest.kt").writeText(
            """
            package pkg

            class SecondTest {
                @Test
                fun a() {}

                @Test
                fun b() {}
            }

            class FirstTest {
                @Test
                fun c() {}
            }
            """.trimIndent(),
        )
        val classes = File(tmp, "classes").apply { mkdirs() }
        File(classes, "FirstTest.class").writeText("")
        File(classes, "SecondTest.class").writeText("")

        val declared = DeclaredTests.declaredIn(listOf(File(tmp, "src")), listOf(classes))
        assertEquals(mapOf("SecondTest" to 2, "FirstTest" to 1), declared.counts)
    }

    @Test
    fun `a nested class does not start a new count`(
        @TempDir tmp: File,
    ) {
        // Indented, so it belongs to the class it sits in. Counting it separately would file the
        // outer class's own tests under a name nothing reports.
        val src = File(tmp, "src/commonTest/kotlin/pkg").apply { mkdirs() }
        File(src, "OuterTest.kt").writeText(
            """
            package pkg

            class OuterTest {
                class Fixture(val n: Int)

                @Test
                fun a() {}
            }
            """.trimIndent(),
        )
        val classes = File(tmp, "classes").apply { mkdirs() }
        File(classes, "OuterTest.class").writeText("")

        assertEquals(
            mapOf("OuterTest" to 1),
            DeclaredTests.declaredIn(listOf(File(tmp, "src")), listOf(classes)).counts,
        )
    }

    @Test
    fun `a results tree is counted however deep it is nested`(
        @TempDir tmp: File,
    ) {
        // A multiplatform test task nests its results a level deeper than a JVM `Test` does. A reader
        // that lists one directory finds nothing there and reports zero — and zero is exactly what
        // the check fails on, so the wrong answer would be indistinguishable from the defect.
        val results = File(tmp, "test-results/macosArm64Test").apply { mkdirs() }
        File(results, "TEST-pkg.FirstTest.xml").writeText("<testsuite name=\"pkg.FirstTest\" tests=\"3\" >")
        val nested = File(results, "binaries").apply { mkdirs() }
        File(nested, "TEST-pkg.SecondTest.xml").writeText("<testsuite name=\"pkg.SecondTest\" tests=\"2\" >")

        assertEquals(5, DeclaredTests.executedIn(File(tmp, "test-results")))
    }

    @Test
    fun `a missing results directory counts as nothing run`(
        @TempDir tmp: File,
    ) {
        assertEquals(0, DeclaredTests.executedIn(File(tmp, "never-written")))
    }
}
