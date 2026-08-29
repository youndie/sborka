plugins {
    alias(libs.plugins.ktlint)
}

// The root's own scripts. `:catalog` applies the plugin itself — applied from here it would be loaded
// by a classloader with no Kotlin plugin under it, and the ktlint plugin looks Kotlin up at apply time.
ktlint {
    version.set(libs.versions.ktlintTool)
}

// The root holds no code. Everything sborka publishes lives in the `build-logic` build and in `:catalog`;
// what is here is the one task that runs everything.

// Compiles the plugins, runs their unit tests, and then APPLIES them in a real build.
//
// The second half is the part that cannot be skipped. Compiling a convention plugin says nothing about
// whether it can be applied: an id derived from the wrong package, a `compileOnly` dependency that
// turns out to be needed at execution time, a task registered under a name nothing produces — none of
// that is a compilation error.
//
// Named `check` so `./gradlew check` at the root runs all of it. A gate that has to be remembered as a
// second command is not a gate.
tasks.register("check") {
    group = "verification"
    description = "Builds the conventions and applies them in a separate build"
    // The root has no `base` plugin, so ktlint's task is not wired into anything on its own.
    dependsOn(tasks.named("ktlintCheck"))
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
    dependsOn(gradle.includedBuild("stand").task(":check"))
}

tasks.register("publishToWip") {
    group = "publishing"
    description = "Publishes the conventions and the version catalog to the wip snapshot repository"
    dependsOn(gradle.includedBuild("build-logic").task(":publishAllPublicationsToWipRepository"))
    dependsOn(":catalog:publishAllPublicationsToWipRepository")
}
