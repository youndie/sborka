// A Kotlin/Native service, applying the convention that stages its binary and the gate that measures
// it. The only module here that links an executable.

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("io.github.youndie.sborka.native-service")
    // APPLIED BY THE REPOSITORY, NOT BY SBORKA, which is the arrangement this module exists to prove.
    // sborka compiles against razves and carries none of it, so a repository that wants the gate says
    // so here - the same way it says which Kotlin it builds with.
    alias(libs.plugins.razves)
}

// BEFORE THE TARGET, AND IT HAS TO BE. The convention configures `binaries.executable` from inside
// `targets.withType(...).configureEach`, which fires the moment `linuxX64()` declares one - so an
// entry point set after that line is set after it was read, and the build fails with "property
// entryPoint has no value available" naming neither the ordering nor this block. This module is the
// convention's first consumer anywhere, which is how the ordering came to be discovered at all.
nativeService {
    entryPoint = "stand.main"
    baseName = "stand-service"
}

// THE TARGET THIS HOST CAN LINK, not a fixed one.
//
// A klib cross-compiles and an executable does not: a mac cannot produce an ELF and a Linux runner
// cannot produce a Mach-O. `kmp-lib` next door gets away with a fixed `linuxX64()` because it only
// ever compiles a klib; this module links, and a fixed target would make the stand green on CI and
// unrunnable on the machine somebody is editing the convention from.
kotlin {
    val mac = System.getProperty("os.name").startsWith("Mac")
    if (mac) macosArm64() else linuxX64()
}

// WHAT THE PROPERTY ACTUALLY DID, read out of what razves wrote rather than assumed from a green
// task.
//
// `sizeBudgetCheck` passes when there is no budget at all, so a run in which the property never
// reached razves looks exactly like a run in which it did. The verdict file names the number, and the
// number is the one `gradle.properties` set: 50MiB is 52,428,800 bytes.
val verifySizeBudget =
    tasks.register("verifySizeBudget") {
        group = "verification"
        description = "Checks that sborka.binaryBudget reached the gate"
        dependsOn("sizeBudgetCheckDebugExecutable")

        val verdict = layout.buildDirectory.file("reports/razves/debugExecutable-budget.txt")
        val expected = providers.gradleProperty("sborka.binaryBudget")
        outputs.upToDateWhen { false }

        doLast {
            val file = verdict.get().asFile
            check(file.isFile) { "the gate wrote no verdict at ${file.path}, so it did not run" }
            val text = file.readText()
            check("52,428,800" in text) {
                "the verdict does not carry the budget ${expected.get()} set in gradle.properties, so " +
                    "the property did not reach razves - a gate with no budget passes every build:\n$text"
            }
            logger.lifecycle("verifySizeBudget: ${text.trim()}")
        }
    }

tasks.named("check") { dependsOn(verifySizeBudget) }
