package io.github.youndie.sborka.internal

import io.github.youndie.sborka.internal.fixture.JoinsFixtureUsed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The joins scan, against BYTECODE THE COMPILER ACTUALLY PRODUCED.
 *
 * The fixtures beside this file are ordinary Kotlin: they are compiled by the same build, and the
 * test copies their class files into a tree shaped like a real one — a `main` output and a `test`
 * output — and points the scan at it. A hand-written class file would test the reader against my idea
 * of what Kotlin emits, which is the thing most worth not assuming: whether a constructor call leaves
 * a class in the constant pool, whether `internal` is mangled, what a file with two classes in it
 * compiles to.
 */
class JoinsTest {
    private val fixturePackage = "io/github/youndie/sborka/internal/fixture"

    private val compiledFixtures: File =
        File(
            JoinsFixtureUsed::class.java.protectionDomain.codeSource.location
                .toURI(),
        )

    private val fixtureSources = File("src/test/kotlin/$fixturePackage")

    private fun arrange(tmp: File): Joins.Report {
        // A `main` output and a `test` output, because "only tests mention it" is read off the
        // directory the classes came out of and not off anything in the class file.
        val main = File(tmp, "classes/kotlin/main/$fixturePackage").apply { mkdirs() }
        val test = File(tmp, "classes/kotlin/test/$fixturePackage").apply { mkdirs() }
        val sources = File(tmp, "src/commonMain/kotlin/$fixturePackage").apply { mkdirs() }

        File(compiledFixtures, fixturePackage)
            .listFiles { file -> file.name.endsWith(".class") }
            .orEmpty()
            .forEach { file ->
                val target = if (file.name.startsWith("JoinsFixtureTestUser")) test else main
                file.copyTo(File(target, file.name), overwrite = true)
            }
        fixtureSources.listFiles { file -> file.name.endsWith(".kt") }.orEmpty().forEach { file ->
            file.copyTo(File(sources, file.name), overwrite = true)
        }

        return Joins.scan(listOf(File(tmp, "classes")), listOf(File(tmp, "src")))
    }

    @Test
    fun `a declaration nothing outside its file mentions is a finding`(
        @TempDir tmp: File,
    ) {
        val report = arrange(tmp)
        val nothing = report.findings.filterNot { it.testsOnly }.map { it.qualifiedName.substringAfterLast('.') }
        assertEquals(
            listOf(
                // Calls a mangled function in another file; nothing calls this one.
                "JoinsFixtureCashier",
                // Holds an implementation; nothing holds this one.
                "JoinsFixtureOpener",
                // Its neighbour mentions it, and the mention does not leave the file.
                "JoinsFixtureSameFileHelper",
                "JoinsFixtureSameFileUser",
                "JoinsFixtureUnused",
                // Mentions another file's class; nothing mentions it.
                "JoinsFixtureUser",
            ),
            nothing,
        )
    }

    @Test
    fun `a declaration only a test mentions is its own kind of finding`(
        @TempDir tmp: File,
    ) {
        // The shape of every one of the four findings this rule exists for: proven by a test, and
        // called by nothing that ships.
        val report = arrange(tmp)
        assertEquals(
            listOf("JoinsFixtureTestOnly"),
            report.findings.filter { it.testsOnly }.map { it.qualifiedName.substringAfterLast('.') },
        )
    }

    @Test
    fun `a mention from another file is a mention`(
        @TempDir tmp: File,
    ) {
        val report = arrange(tmp)
        assertEquals(
            emptyList<String>(),
            report.findings.map { it.qualifiedName }.filter { it.endsWith(".JoinsFixtureUsed") },
        )
    }

    @Test
    fun `an internal declaration is not considered`(
        @TempDir tmp: File,
    ) {
        // `internal` is `public` in bytecode and, for a class, unmangled — so the source is the only
        // place that knows.
        val report = arrange(tmp)
        assertEquals(
            emptyList<String>(),
            report.findings.map { it.qualifiedName }.filter { it.endsWith(".JoinsFixtureInternal") },
        )
    }

    @Test
    fun `a suppression is read out of the source and counted`(
        @TempDir tmp: File,
    ) {
        val report = arrange(tmp)
        assertEquals(
            emptyList<String>(),
            report.findings.map { it.qualifiedName }.filter { it.endsWith(".JoinsFixtureSuppressed") },
        )
        assertEquals(
            listOf("io.github.youndie.sborka.internal.fixture.JoinsFixtureSuppressed"),
            report.suppressed,
        )
    }

    @Test
    fun `a scan that read no class files is not an empty report`(
        @TempDir tmp: File,
    ) {
        // The caller fails on this rather than printing "found 0", and the number is what it fails on.
        val report = Joins.scan(listOf(File(tmp, "nothing-here")), listOf(File(tmp, "none")))
        assertEquals(0, report.classesRead)
        assertEquals(emptyList<Joins.Finding>(), report.findings)
    }

    @Test
    fun `a function nothing calls is a finding, and the mangled ones are not`(
        @TempDir tmp: File,
    ) {
        // THE MANGLED NAME IS THE WHOLE POINT OF THIS CASE. `charge` takes a value class, so the JVM
        // knows it as `charge-<hash>` — and a comparison against the source's `charge` reports every
        // function in the portfolio that takes a `Color`, a `Dp` or a `Duration` as called by nobody.
        // On shashki that was five of the first six findings, and every one of them wrong.
        //
        // `open` is the other half: it is declared on an interface and called through an
        // implementation, so the constant pool names `JoinsFixtureAdapter.open` and never the port.
        // Without the class hierarchy every interface function is a finding.
        //
        // `joinsFixtureInline` is neither: an inline function leaves no reference behind at all, so
        // the only honest thing to do is not ask about it.
        val report = arrange(tmp)
        assertEquals(
            listOf("JoinsFixtureCashier.ring", "JoinsFixtureOpener.use"),
            report.functionFindings.map { finding ->
                finding.qualifiedName
                    .split('.')
                    .takeLast(2)
                    .joinToString(".")
            },
        )
    }
}
