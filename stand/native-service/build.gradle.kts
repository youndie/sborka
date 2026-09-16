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

// THE ALLOCATOR OPTION REACHED THE COMPILER, read off the link task rather than believed.
//
// The convention sets `fixedBlockPageSize` on the binary it already configures. Whether that lands
// in the arguments the compiler is invoked with is the one thing the DSL cannot show: a binary
// option set on the wrong container, or on a container a later block replaces, leaves a green build
// and a service that is OOM-killed weeks later under a limit — which is precisely how this option
// came to be measured in the first place.
//
// So the stand asks the link task what it will pass. This is the question the skill used to leave
// open with "has not been tried on any service".
val verifyAllocatorPageSize =
    tasks.register("verifyAllocatorPageSize") {
        group = "verification"
        description = "Checks that the convention's fixedBlockPageSize reached the linked binary"
        outputs.upToDateWhen { false }

        // ON THE BINARY, NOT ON THE TASK'S COMPILER ARGUMENTS — and the difference cost a red run to
        // find. `binaryOption` does not land in `toolOptions.freeCompilerArgs`; KGP keeps it on the
        // binary as `binaryOptions` and turns it into `-Xbinary=` when it invokes the compiler. A
        // check reading the arguments finds an empty list on a build that is perfectly correct, and
        // reports the convention broken.
        //
        // EXECUTABLES ONLY. The convention configures `binaries.executable`, so the test binary link
        // task has no such option and never should; requiring it there is a guard failing a build for
        // doing exactly what it was asked.
        val options =
            provider {
                tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink::class.java)
                    .filter { it.binary is org.jetbrains.kotlin.gradle.plugin.mpp.Executable }
                    .associate { it.name to it.binary.binaryOptions }
            }

        doLast {
            val byTask = options.get()
            check(byTask.isNotEmpty()) {
                "no executable link task at all — this module links one, so finding none means this " +
                    "check would have passed by finding nothing"
            }
            val without = byTask.filterValues { it["fixedBlockPageSize"] != "16" }
            check(without.isEmpty()) {
                "the convention's allocator option did not reach ${without.keys}: " +
                    without.entries.joinToString("; ") { (task, opts) -> "$task has $opts" }
            }
            logger.lifecycle(
                "verifyAllocatorPageSize: fixedBlockPageSize=16 on ${byTask.keys.joinToString(", ")}",
            )
        }
    }

tasks.named("check") { dependsOn(verifyAllocatorPageSize) }

// THE BINARY IS ACTUALLY THERE, because the task that stages it can succeed having staged nothing.
//
// `Sync` with no source is `NO-SOURCE` and a green build, and that is not hypothetical: while making
// `stageNativeImage` work with the configuration cache (#76) an intermediate shape resolved its
// sources when the cache entry was written, so after a `clean` every later run staged nothing and
// said so only as a four-letter task status. Everything downstream — a Dockerfile's `COPY`, an image
// build in CI — would have failed a step later, naming a path and not the cause.
//
// The size floor is deliberate. A zero-byte file at the right path would satisfy "it exists", and
// the failure this guards against produces exactly that class of artefact.
val verifyStagedImage =
    tasks.register("verifyStagedImage") {
        group = "verification"
        description = "Checks that stageNativeImage actually put a binary at build/native-image/<baseName>"
        dependsOn("stageNativeImage")
        outputs.upToDateWhen { false }

        val staged = layout.buildDirectory.file(nativeService.baseName.map { "native-image/$it" })
        doLast {
            val binary = staged.get().asFile
            check(binary.isFile) {
                "stageNativeImage staged nothing: ${binary.path} does not exist. The task is a Sync, " +
                    "so an empty source set is NO-SOURCE and a green build"
            }
            check(binary.length() > 1024) {
                "${binary.path} is ${binary.length()} bytes, which is not a linked executable"
            }
            logger.lifecycle("verifyStagedImage: ${binary.name}, ${binary.length()} bytes")
        }
    }

tasks.named("check") { dependsOn(verifyStagedImage) }

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

        // FOUND, NOT SPELLED — and the rename that forced this is the argument for it. This used to
        // name `sizeBudgetCheckDebugExecutable` and read `reports/razves/debugExecutable-budget.txt`;
        // razves 0.1.0.31 put the target into both (`sizeBudgetCheckMacosArm64DebugExecutable`,
        // `reports/razves/<target>/…`) because a task name that assumed one native target was the
        // same defect this module's sibling carries for the staged path. A guard with a coordinate
        // written inside it loses its subject at the first rename and then passes for the wrong
        // reason — or, as here, fails naming a task rather than the change.
        dependsOn(tasks.matching { it.name.startsWith("sizeBudgetCheck") })

        val reports = layout.buildDirectory.dir("reports/razves")
        val expected = providers.gradleProperty("sborka.binaryBudget")
        outputs.upToDateWhen { false }

        doLast {
            val verdicts =
                reports
                    .get()
                    .asFile
                    .walkTopDown()
                    .filter { it.isFile && it.name.endsWith("-budget.txt") }
                    .toList()
            check(verdicts.isNotEmpty()) {
                "the gate wrote no verdict under ${reports.get().asFile.path}, so it did not run"
            }

            // RELEASE ONLY, because that is what razves 0.1.0.31 budgets: `budget` stopped applying
            // to the debug binary — `fix(gradle-plugin)!: apply the size budget to what ships, not
            // to debug` — and a debug verdict now reads "no size rule is set for it", which is
            // correct and is not this property failing to arrive. Until that release this guard read
            // the debug file and would now fail for the wrong reason.
            val shipped = verdicts.filter { "release" in it.name.lowercase() || "release" in it.parentFile.name.lowercase() }
            check(shipped.isNotEmpty()) {
                "no release verdict among ${verdicts.map { it.name }} — the budget applies to what " +
                    "ships, so a run with none proves nothing"
            }
            shipped.forEach { file ->
                val text = file.readText()
                check("52,428,800" in text) {
                    "${file.name} does not carry the budget ${expected.get()} set in gradle.properties, " +
                        "so the property did not reach razves - a gate with no budget passes every " +
                        "build:\n$text"
                }
            }
            logger.lifecycle(
                "verifySizeBudget: ${shipped.size} of ${verdicts.size} verdicts are for a shipped " +
                    "binary, all naming ${expected.get()}",
            )
        }
    }

tasks.named("check") { dependsOn(verifySizeBudget) }

