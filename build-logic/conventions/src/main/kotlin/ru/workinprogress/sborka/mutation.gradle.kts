package ru.workinprogress.sborka

import ru.workinprogress.sborka.internal.PitestArguments
import ru.workinprogress.sborka.internal.SborkaSettings

// Mutation testing as a run rather than an exercise.
//
// Seven mutations made by hand in one repository found two guards NOTHING caught, and two tests that
// passed for a different reason than the one written in their names. Such a test is green, looks like
// coverage, and stays green if the check it was written for is removed. Hence a task: the same
// question asked in order, rather than wherever attention happened to reach.
//
// THE TASK IS NOT PART OF `check` AND MUST NOT BE. It is slow, and its result is not a threshold but a
// list of survivors, each of which is read on its own — a survival percentage is as meaningless a
// number as a coverage percentage. And a survivor is a hypothesis, not a verdict: it says a mutation
// went unnoticed, not which lever is the right one to add.
//
// Applied per module, on purpose: the modules worth mutating are the ones holding code that runs in
// production, and a benchmark or a fuzz target is neither.

plugins {
    id("ru.workinprogress.sborka.base")
}

val pitestVersion = providers.gradleProperty("sborka.pitestVersion").orElse("1.25.9").get()
val pitestJunitVersion = providers.gradleProperty("sborka.pitestJunit5Version").orElse("1.2.3").get()
val targetPackage =
    providers.gradleProperty("sborka.mutationPackage").orNull
        ?: error(
            "sborka.mutationPackage is not set. pitest needs to be told which classes to mutate and " +
                "which tests to run; without it the run either mutates the whole classpath or nothing. " +
                "Set it in gradle.properties, for example `sborka.mutationPackage=io.github.youndie.bochka`.",
        )

plugins.withId("org.jetbrains.kotlin.jvm") {
    val pitest = configurations.create("pitest")
    dependencies.add("pitest", "org.pitest:pitest-command-line:$pitestVersion")
    dependencies.add("pitest", "org.pitest:pitest-junit5-plugin:$pitestJunitVersion")

    // Everything the task needs, resolved HERE, at configuration time, into values the configuration
    // cache can hold: a launcher provider, three file collections, a string. A `SourceSet` captured
    // into the task instead is refused by name, and the message names the type rather than the line.
    val sourceSets = extensions.getByType<SourceSetContainer>()
    val main = sourceSets.getByName("main")
    val test = sourceSets.getByName("test")
    val launcher =
        extensions
            .getByType<JavaToolchainService>()
            .launcherFor { languageVersion.set(JavaLanguageVersion.of(SborkaSettings.jvmToolchain(project))) }

    // Only this module's code is mutated, but everything it lives on goes on the path.
    val sourceDirs = files(main.allSource.srcDirs)
    val mutable = main.output.classesDirs
    val fullPath = test.runtimeClasspath

    // Narrow the run to one class or family: `-PmutationTarget=…S3Handler`. A whole module takes tens
    // of minutes, and narrowing it is the only way to ask about ONE place and get the answer today.
    val target = providers.gradleProperty("mutationTarget").orElse("$targetPackage.*")
    val reportDir = layout.buildDirectory.dir("reports/pitest")

    tasks.register<JavaExec>("mutationTest") {
        group = "verification"
        description = "Breaks this module one place at a time and says what the tests did not notice"

        // The classes have to be compiled and the tests green beforehand: pitest refuses to mutate
        // code whose suite fails without a mutation, and that is the right refusal.
        dependsOn(tasks.named("test"))

        classpath = pitest
        mainClass.set("org.pitest.mutationtest.commandline.MutationCoverageReport")
        javaLauncher.set(launcher)
        outputs.dir(reportDir)

        argumentProviders.add(
            PitestArguments(
                reportDir = reportDir,
                targetClasses = target,
                targetTests = "$targetPackage.*",
                sourceDirs = sourceDirs,
                mutableCodePaths = mutable,
                runtimeClasspath = fullPath,
            ),
        )
    }
}
