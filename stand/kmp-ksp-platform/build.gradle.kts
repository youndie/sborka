plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.ksp)
    id("io.github.youndie.sborka.kmp")
    id("io.github.youndie.sborka.lint")
}

// THE NEGATIVE CONTROL for the KSP wiring in `sborka.kmp`: the same plugin, a processor on a
// PLATFORM source set, and nothing on common metadata.
//
// A real module of this shape exists — kompot runs a screenshot processor over `desktopTest` — and
// it is the one the wiring would break rather than help: it would switch off the very task that
// generates its code, leaving tests that compile against nothing and a green build. So the
// convention asks whether a processor sits on `kspCommonMainMetadata`, which is false here, and
// leaves this module entirely alone.
//
// That absence is what the test below checks, and it checks it the only way that cannot pass by
// accident: by USING the generated code. If the convention ever starts firing here, the per-target
// task is disabled and `platformRegistry` stops resolving.

kotlin {
    jvm()
    linuxX64()
}

dependencies {
    add("kspJvmTest", project(":ksp-processor"))
}

// Written out by hand, and that is the point of this module: a per-target source set is the case
// `sborka.kmp` does not wire, so the line stays where a person put it.
kotlin.sourceSets.getByName("jvmTest") {
    kotlin.srcDir("build/generated/ksp/jvm/jvmTest/kotlin")
}

// AND THE SAME ANSWER ASKED DETERMINISTICALLY, because the test above has one blind spot: on an
// incremental build the previous run's generated file is still on disk, so a module that has just
// STOPPED generating still compiles. Caught the first time this control was mutated — the mutation
// only failed after a `clean`. CI checks out fresh and would have shown it; a person's machine would
// not.
val verifyKspLeftAlone =
    tasks.register("verifyKspLeftAlone") {
        group = "verification"
        description = "Checks that sborka.kmp did not switch off this module's per-target KSP task"
        outputs.upToDateWhen { false }

        val generator =
            project.tasks
                .named("kspTestKotlinJvm")
                .get()
                .enabled

        doLast {
            check(generator) {
                "kspTestKotlinJvm is disabled. sborka.kmp wires the common-metadata case and " +
                    "switches off the per-target tasks; applied to a module like this one it takes " +
                    "away the task that generates its code, and the build stays green on whatever " +
                    "the last run left behind."
            }
        }
    }

tasks.named("check") { dependsOn(verifyKspLeftAlone) }
