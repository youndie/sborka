plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.ksp)
    id("io.github.youndie.sborka.kmp")
    id("io.github.youndie.sborka.lint")
}

// THE MODULE `sborka.kmp` WIRES KSP FOR, and the shape is the one seven modules of kompot carry: the
// annotated types are in `commonMain`, so the processor runs once over common metadata and every
// target compiles what it produced.
//
// What is written here is what a repository still writes for itself — the processor, and the
// arguments it takes. The three paragraphs that used to sit beside it are the convention's now.
//
// Two targets rather than one, because one hides the defect this arrangement exists to prevent:
// per-target output lands in a platform source set, where the metadata a consumer's `commonMain`
// compiles against can never see it. A single-target module never notices.
kotlin {
    jvm()
    linuxX64()
}

dependencies {
    add("kspCommonMainMetadata", project(":ksp-processor"))
}

// WHAT THE WIRING DID, ASSERTED RATHER THAN INFERRED FROM A GREEN BUILD.
//
// `GeneratedCodeTest` covers the half that produces code. This covers the half that produces
// nothing: the per-target KSP tasks are switched off because they have no processor, and a disabled
// task is the classic way to get a green and empty build — an exit code cannot tell "switched off on
// purpose" from "never wired at all".
//
// The task list is read INSIDE the registration block, which Gradle runs when the task is realised,
// and not from `afterEvaluate`: `kspCommonMainKotlinMetadata` does not exist yet when this script's
// `afterEvaluate` runs, and asking for it there fails with "task with name ... not found".
val verifyKspWiring =
    tasks.register("verifyKspWiring") {
        group = "verification"
        description = "Checks that sborka.kmp wired the common-metadata processor and switched off the rest"
        outputs.upToDateWhen { false }

        val metadata =
            project.tasks
                .named("kspCommonMainKotlinMetadata")
                .get()
                .enabled
        val perTarget =
            project.tasks
                .matching { it.name.startsWith("ksp") && it.name != "kspCommonMainKotlinMetadata" }
                .associate { it.name to it.enabled }

        doLast {
            check(metadata) { "kspCommonMainKotlinMetadata is disabled — nothing generates anything" }
            // A run that found no per-target tasks would pass the next check by finding nothing.
            check(perTarget.isNotEmpty()) {
                "no per-target KSP tasks were found at all, so this check proved nothing about them"
            }
            val stillOn = perTarget.filterValues { it }.keys.sorted()
            check(stillOn.isEmpty()) {
                "these per-target KSP tasks have no processor and are still enabled: $stillOn. " +
                    "They read the metadata output as a source directory, which Gradle reports as an " +
                    "undeclared dependency between tasks."
            }
        }
    }

tasks.named("check") { dependsOn(verifyKspWiring) }
