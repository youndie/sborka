package io.github.youndie.sborka

import io.github.youndie.sborka.internal.DeclaredTests
import io.github.youndie.sborka.internal.SborkaSettings
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// How a test run behaves and how it is believed.
//
// Three things, and only the first is cosmetic:
//
//  * JUnit Platform, and a failure readable IN THE RUN LOG. Gradle's default prints an exception
//    type and a line; CI does not keep the HTML report and the runner cannot be held in your hands,
//    so a test that only fails there gets debugged blind.
//
//  * THE VERSION OF JUNIT THE CATALOG NAMES IS THE VERSION THE TESTS RUN ON. `kotlin("test")`
//    resolves to `kotlin-test-junit5`, which carries a Jupiter of its own, and it wins by default —
//    so a repository can name a version in its catalog, explain the choice in a comment beside it,
//    and run something else. A number that names something other than what runs is worse than no
//    number: the argument next to it is then about a version nobody is using.
//
//  * EVERY @Test THAT WAS DECLARED MUST HAVE BEEN EXECUTED. See below.

// A `Test` task exists only where a JVM test source set does, so no plugin guard is needed: in a
// module with none, `withType` configures nothing.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

// The platform, enforced, and only when the repository said which one.
//
// `enforcedPlatform` rather than `platform`: the point is to overrule the Jupiter that arrives
// through `kotlin-test-junit5`, and a plain platform loses to it. Skipped entirely when
// `sborka.junitVersion` is unset, because pinning a JUnit a repository never named is the same class
// of mistake in the other direction.
//
// `testImplementation` only. A multiplatform module names its configurations after its targets —
// `jvmTestImplementation`, and one per target — so there is no single name to add to; such a module
// declares the platform in its own `jvmTest` source set, where the choice is visible.
val junitVersion = providers.gradleProperty("sborka.junitVersion").orNull
if (junitVersion != null) {
    configurations.matching { it.name == "testImplementation" }.configureEach {
        dependencies.addLater(
            provider { project.dependencies.enforcedPlatform("org.junit:junit-bom:$junitVersion") },
        )
    }
}

// EVERY @Test THAT WAS DECLARED MUST HAVE BEEN EXECUTED, checked after every test run.
//
// JUnit 5 does not run a `@Test` method that returns something, and does not warn either: the method
// is simply not a test, and the class reports one fewer. Kotlin makes that easy to write by accident,
// because an expression-bodied test takes its return type from its last expression and
// `kotlin.test.assertNotNull` returns the value it checked. So a test ending in `assertNotNull(…)`
// compiles, is annotated, and never runs.
//
// THREE OF THEM WERE FOUND BY ACCIDENT in the repository this check comes from, two of them months
// old. One covered a counter whose subtract and clamp sat in the order that zeroes a subscriber's
// remaining allowance on any consumption taking more than half of it. The defect was covered. The
// cover had never executed once.
//
// COVERED FOR A JVM `Test` ONLY, and the second block below is what the other kinds get instead.
if (SborkaSettings.flag(project, "sborka.declaredTests", default = true)) {
    tasks.withType<Test>().configureEach {
        val resultsDir = reports.junitXml.outputLocation
        val sourceRoot = layout.projectDirectory.dir("src").asFile
        val taskName = name

        doLast {
            // A FILTERED TASK IS RUNNING A SUBSET ON PURPOSE, so "declared and not run" is its job
            // rather than a defect. AN INCLUDE SELECTS AND AN EXCLUDE NARROWS, and treating them the
            // same cost this check a whole module once: a task with an include pattern is deliberately
            // running a subset, so there is nothing to compare; a task with an exclude runs everything
            // else, so it is still worth checking — with the excluded classes taken out of what is
            // expected rather than the check taken out of the build.
            //
            // Announced rather than skipped quietly. A check that silently declines to check is the
            // same shape of silence it exists to catch.
            val included = (this as Test).filter.includePatterns + DeclaredTests.commandLinePatterns(filter)
            if (included.isNotEmpty()) {
                logger.lifecycle("$taskName: filtered to ${included.joinToString()} — not checked for unrun tests")
                return@doLast
            }

            // READ AT EXECUTION TIME. Captured in the `configureEach` above it comes back empty: the
            // Kotlin plugin has not set the source set's output when this block is configured, and a
            // FileCollection captured then resolves to nothing rather than to the classes — a check
            // that silently sees no test classes and passes.
            val declared = DeclaredTests.declaredIn(sourceRoot, testClassesDirs, filter.excludePatterns)
            if (declared.isEmpty()) return@doLast

            val reported = DeclaredTests.reportedIn(resultsDir.get().asFile)

            // THE VACUITY GUARD, and it comes first. A module whose sources declare tests and whose
            // results directory is empty has proved nothing — and without this the comparison below
            // would pass, having found nothing to compare.
            check(reported.isNotEmpty()) {
                "$taskName: ${declared.size} test class(es) declare @Test and no results were written to " +
                    "${resultsDir.get().asFile}. A run that executed nothing passes every comparison."
            }

            val shortfalls = DeclaredTests.shortfalls(declared, reported)
            check(shortfalls.isEmpty()) {
                "these declare tests that JUnit did not run — the commonest cause is an expression-bodied " +
                    "@Test whose last expression returns a value, which makes the method non-void and " +
                    "therefore not a test:\n  " + shortfalls.joinToString("\n  ")
            }

            logger.lifecycle("$taskName: every @Test in ${declared.size} class(es) was executed")
        }
    }

    // AND EVERY OTHER KIND OF TEST TASK, WHICH IS THE HALF THE COMPARISON CANNOT REACH.
    //
    // `linuxX64Test`, `iosSimulatorArm64Test`, `wasmJsBrowserTest` and `jsNodeTest` are
    // `AbstractTestTask`s of the Kotlin plugin's own kinds rather than `Test` tasks, so nothing above
    // sees them. This file used to say so and stop there.
    //
    // What can be asked of them is smaller and still worth asking. THE PER-CLASS SHORTFALL STAYS A
    // JVM THING because it needs the classes the task compiled, and a Kotlin/Native test binary is
    // one executable with no class files to read declarations out of. A count needs none.
    //
    // A SUITE THAT RAN NOTHING IS GREENER THAN ONE WITH A FAILURE, which is the exact shape of a wasm
    // target that looked checked and was not. This is lifted from the repository where that was
    // found, where it was written by hand in a root build because the conventions had nowhere to put
    // it — three items had closed against "no browser on the build box" while the suite quietly ran
    // zero tests.
    //
    // A TASK GRADLE SKIPPED NEVER RUNS ITS `doLast`, so `NO-SOURCE` and a task disabled because the
    // machine has no browser both stay green — and saying which of those happened is the
    // repository's job, not this one's.
    tasks.withType<AbstractTestTask>().configureEach {
        if (this is Test) return@configureEach

        val resultsDir = reports.junitXml.outputLocation
        val taskPath = path

        doLast {
            val executed = DeclaredTests.executedIn(resultsDir.get().asFile)
            check(executed > 0) {
                "$taskPath wrote no test results to ${resultsDir.get().asFile}. A suite that ran " +
                    "nothing passes every comparison there is, which is why it is not a check."
            }
            logger.lifecycle("$taskPath: $executed test(s)")
        }
    }
}
