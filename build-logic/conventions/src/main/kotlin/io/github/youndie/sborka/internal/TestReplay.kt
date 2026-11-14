package io.github.youndie.sborka.internal

import org.gradle.api.tasks.testing.AbstractTestTask
import java.io.File

// What a test task's previous run left behind, read before Gradle decides to skip the next one.
//
// In a class of its own for the reason `DeclaredTests` gives: a spec in a `.gradle.kts` file that calls
// one of the script's own functions captures the script object, and the configuration cache refuses
// to serialise it.
object TestReplay {
    private val suiteHeader = Regex("""<testsuite\b[^>]*>""")

    private val failures = Regex("""\bfailures="(\d+)"""")

    private val errors = Regex("""\berrors="(\d+)"""")

    /**
     * Whether Gradle may call [task] up to date: always when a failed test fails the task itself, since
     * such a run is never recorded; under ignored failures only when the previous run's JUnit XML says
     * nothing failed; and never when there is no XML to ask.
     */
    fun mayBeSkipped(task: AbstractTestTask): Boolean {
        if (!task.ignoreFailures) return true
        val xml = task.reports.junitXml
        if (!xml.required.get()) return false
        return failedIn(xml.outputLocation.get().asFile) == 0
    }

    /**
     * How many test cases failed or errored in the JUnit XML under [resultsDir], however deep it is
     * nested (see `DeclaredTests.executedIn`, which walks the tree for the same reason).
     *
     * Read from each suite's opening tag only, so a failure message that happens to contain the text
     * `failures="3"` is not counted. A missing directory is zero: nothing ran, so nothing failed — and
     * whether nothing running is itself a defect is `sborka.test`'s question, not this one's.
     */
    fun failedIn(resultsDir: File): Int {
        if (!resultsDir.isDirectory) return 0

        return resultsDir
            .walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .sumOf { file ->
                val header = suiteHeader.find(file.readText(Charsets.UTF_8))?.value ?: return@sumOf 0
                listOf(failures, errors).sumOf { attribute ->
                    attribute
                        .find(header)
                        ?.groupValues
                        ?.get(1)
                        ?.toInt() ?: 0
                }
            }
    }
}
