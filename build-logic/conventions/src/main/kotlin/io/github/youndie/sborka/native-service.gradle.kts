package io.github.youndie.sborka

import io.github.youndie.sborka.internal.NativeImageReference
import io.github.youndie.sborka.internal.SborkaSettings
import io.github.youndie.sborka.internal.SizeGate
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.Executable
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

// A Kotlin/Native server, from an entry point to a file a Dockerfile can COPY.
//
// Two repositories in the portfolio ship one, and their Dockerfiles differ by a single path segment:
// the same builder image, the same distroless runtime, the same hand-written line dragging
// `libcrypt.so.1` across from the builder. The third has the pattern too, in a container it built for
// a sample. What none of them share is where the binary lands — `bin/native/releaseExecutable` in one,
// `bin/linuxX64/releaseExecutable` in the other — which is exactly the kind of difference a COPY line
// discovers at image build time.
//
// So this plugin does two things and refuses a third. It names the binary, and it stages it under one
// path regardless of how the target was declared. It does NOT write the Dockerfile into the build: the
// runtime image is a decision about certificates, shared libraries and a base image's glibc, and those
// belong in a file a person reads. `writeNativeDockerfile` puts sborka's reference one on disk, once,
// and then it is the repository's.

plugins {
    id("io.github.youndie.sborka.base")
}

/** How the executable is named and where it starts. */
interface NativeServiceExtension {
    /** The fully qualified `main`. Kotlin/Native has no manifest to find it in. */
    val entryPoint: Property<String>

    /** The file name, without `.kexe`. Defaults to the module name. */
    val baseName: Property<String>

    /**
     * The allocator's page size in KiB — `-Xbinary=fixedBlockPageSize`. 16 by default, which is right
     * for many threads and a small heap (resident memory); 256, the compiler's own, is right for a
     * heap of gigabytes and few threads (the collector's pause). `0` leaves the compiler's default.
     * Both measurements are under `allocatorPageSize.convention` below.
     */
    val allocatorPageSize: Property<Int>
}

private val neededLine = Regex("Shared library: \\[(.+)]")

val nativeService = extensions.create<NativeServiceExtension>("nativeService")
nativeService.baseName.convention(project.name)

// THE ALLOCATOR PAGE SIZE, SET BY DEFAULT BECAUSE THE DEFAULT IS THE ONE THAT GETS A SERVICE KILLED.
//
// The Kotlin/Native allocator keeps a page per size class PER THREAD — 256 KiB each — and a thread
// holds its pages for as long as it lives, so resident memory follows the THREAD COUNT rather than
// the live heap. No GC setting bounds it: these are pages, not objects. `Dispatchers.IO` grows
// threads under load, and the result is a service that passes every test and is OOM-killed under a
// container limit.
//
// Measured on katcher, same binary and image, `--memory=192m --cpus=1`, 50 concurrent requests:
//
//   default                    RSS 56-68 MB at rest, 252-329 MB peak, survived 192Mi 0/8 (exit 137)
//   fixedBlockPageSize=16      RSS 22-26 MB at rest,  47-62 MB peak, survived 192Mi 8/8
//
// 16 KiB is therefore the convention rather than a suggestion in a document somebody may not read.
// It is a MEASURED value and not a law: a service whose own measurement says otherwise sets its own,
// and `allocatorPageSize = 0` leaves the compiler's default entirely. What is not on offer is
// forgetting it exists.
//
// AND THE PAGE SIZE CUTS THE OTHER WAY TOO: A SMALL PAGE IS MORE PAGES, AND THE COLLECTOR WALKS THEM
// WITH THE WORLD STOPPED.
//
// At the end of marking, before the world resumes, every thread's allocator and the heap run
// `PageStore::PrepareForGC` for every size class (Kotlin 2.4.20:
// `kotlin-native/runtime/src/alloc/custom/cpp/PageStore.hpp:24`, called from
// `gc/common/cpp/MainGCThread.hpp:56-69`). It walks the used-page list to its tail and frees every
// page the previous sweep emptied, one at a time: linear in the number of pages, inside the pause.
// The same heap in 16 KiB pages is sixteen times as many.
//
// Measured on an in-memory store holding about 2 GB live on three threads, CMS, the two builds
// interleaved on one host, three runs each (stop-the-world pauses over 60 s of write churn):
//
//   fixedBlockPageSize=16      pause p50  8.0-10.0 ms, p99 84-139 ms, RSS peak 4 237-4 244 MB
//   fixedBlockPageSize=256     pause p50 0.76-0.83 ms, p99  8-18 ms,  RSS peak 4 290-4 304 MB
//
// Tenfold on the pause for 1 % of resident memory. Controls moved the objects marked 4.7x and the
// garbage made during marking 10x, and the pause followed neither: it follows the page count, so it
// grows with the heap. That measurement's report is not public; the mechanism is the runtime source
// named above, which is.
//
// SO THE RULE HAS TWO SIDES, and the default is the side the services this was written for are on:
//
//   many threads, a heap of tens of megabytes    16    what dies is resident memory under a limit
//   few threads, a heap of gigabytes             256   what hurts is the collector's pause
//
// A service in between measures both — peak memory from the cgroup under its limit, and the pause
// from the GC log — rather than picking a side by analogy. The per-thread cost comes back at 256 KiB
// with every thread that touches a size class, so a large heap served by a hundred threads pays both
// and has to choose.
//
// AND IF A SERVICE SWITCHES TO `-Xallocator=std`, THE REFERENCE IMAGE'S `MALLOC_ARENA_MAX=2` HAS TO
// BE RE-MEASURED WITH IT. Each is harmless alone and the pair is not: on a Ktor service with no
// database, `std` alone peaked at 39.3 MB and survived ten runs of ten, `std` with the arena cap
// peaked at 413.7 MB and survived seven. That is the one combination this default does not protect
// anybody from, so it is written where the allocator is chosen rather than only in the image.
//
// NOT GATED TO LINUX, unlike `--as-needed` in `sborka.kmp`. This is an allocator option every
// Kotlin/Native backend accepts, and a macOS development binary that allocates like the one that
// ships is the point of having the target at all.
nativeService.allocatorPageSize.convention(16)

plugins.withId("org.jetbrains.kotlin.multiplatform") {
    extensions.configure<KotlinMultiplatformExtension> {
        targets.withType<KotlinNativeTarget>().configureEach {
            binaries.executable {
                entryPoint = nativeService.entryPoint.get()
                baseName = nativeService.baseName.get()

                // On the binary the convention already configures, which is the whole reason this
                // lives here: a service adding `binaryOption` to its own `binaries.executable` block
                // beside this one is two blocks configuring one container, and which of them wins is
                // a question nobody should have to answer per repository.
                val pageSize = nativeService.allocatorPageSize.get()
                if (pageSize > 0) binaryOption("fixedBlockPageSize", pageSize.toString())
            }
        }
    }
}

// THE SIZE BUDGET, and only where a repository asked for one.
//
// `sborka.binaryBudget=50MiB` in `gradle.properties` sets the ceiling razves fails the build over;
// the gate itself is razves' `sizeBudgetCheck`, already wired into `check` by the plugin. No
// property, no configuration and nothing registered - a gate that arrives with a dependency bump is
// a gate people switch off before they read it.
//
// **The repository applies razves, this only configures it.** Exactly what `sborka.kmp` does with
// the Kotlin plugin, and for the same two reasons: the version belongs to the repository whose build
// it is, and a convention that CARRIED razves would put its jar on the build classpath of every
// repository taking any sborka convention, including the ones that ship no binary.
val binaryBudget = SborkaSettings.binaryBudget(project)
if (binaryBudget != null) {
    plugins.withId("io.github.youndie.razves") { SizeGate.wire(project, binaryBudget) }

    // A PROPERTY NOBODY READS IS WORSE THAN NO PROPERTY. Set the budget, forget the plugin line, and
    // the build stays green forever while measuring nothing - which is the failure this repository's
    // own conventions are full of guards against. So it is named, with the line to add.
    afterEvaluate {
        check(plugins.hasPlugin("io.github.youndie.razves")) {
            "${SborkaSettings.BINARY_BUDGET} is set on ${project.path} and nothing enforces it: " +
                "the gate is razves, and this project does not apply it. Add " +
                "id(\"io.github.youndie.razves\") version \"<version>\" to its plugins block, or " +
                "remove the property - a budget nothing checks is a build that passes forever."
        }
    }
}

// ONE PATH, whatever the target was called.
//
// `linuxX64()` puts its output under `bin/linuxX64/`; `linuxX64("native")` puts it under `bin/native/`.
// Both are reasonable and both are in use here, and a Dockerfile copied between the two repositories
// fails at image build time with "file not found" — naming the path and not the difference. Everything
// downstream reads `build/native-image/` instead.
// Resolved before the task is registered so that the task's own lambdas need nothing from the
// script: see the comment inside.
val binDirectory = layout.buildDirectory.dir("bin")
val releaseLinkTasks = tasks.matching { it.name.startsWith("linkReleaseExecutable") }

// WHERE THE BINARY ENDED UP, for the one file that has to name it: the reference Dockerfile's
// `COPY`. Filled in below, once the target count is known.
val stagedPath = objects.property<String>().convention(nativeService.baseName)

val stageNativeImage =
    tasks.register<Sync>("stageNativeImage") {
        group = "distribution"
        description = "Copies the release executable to build/native-image/ under a stable name"

        // NOTHING HERE MAY REACH THE SCRIPT OBJECT, and that is what the two lines above the lambdas
        // are for. A `provider { }` inside a precompiled script plugin that calls `tasks` or
        // `layout` captures the script instance itself, which the configuration cache refuses to
        // serialise — `cannot serialize Gradle script object references`, naming the task and
        // nothing about which lambda (#76). Pulled out, the values are ordinary Gradle types and
        // the lambdas below capture only their own parameters.
        //
        // The collection is LIVE: `matching` is evaluated as tasks are registered, so the link
        // tasks the Kotlin plugin adds after this convention is applied are still in it. Taking it
        // as the source below carries the dependency, so there is no `dependsOn` here to forget.
        //
        // THE LINK TASKS' OUTPUTS, AND NOT A SCAN OF `build/bin`.
        //
        // The scan is what made this task uncacheable, and making it lazy is not enough: a copy spec
        // resolves its sources while the configuration cache entry is being written, so a directory
        // walk is answered from whatever `build/bin` held at that moment. After a `clean` that is
        // nothing — and the entry stored then reports `NO-SOURCE` on every later run with the binary
        // sitting right there. A task that quietly stages nothing is worse than the failure it
        // replaced, and this one was measured here before the shape below was chosen.
        //
        // `include("*.kexe")` and not `**/*.kexe`: on Apple targets the same directory holds
        // `<name>.kexe.dSYM/`, whose `Contents/Resources/DWARF/<name>.kexe` is a file with that
        // extension several levels down. The greedy pattern stages the debug symbols instead, and
        // `rename` below gives them the binary's name.
        //
        // The sources themselves are added further down, once the module has finished declaring its
        // targets: what the layout is depends on how many there are (#80).
        into(layout.buildDirectory.dir("native-image"))

        // `rename { nativeService.baseName.get() }` reads the extension through the script and is
        // the same defect one line further down; the property is captured instead.
        val stagedName = nativeService.baseName
        rename { stagedName.get() }

        // WHAT THE BINARY DECLARES, IN THE LOG OF THE BUILD THAT CHANGED IT.
        //
        // The runtime image carries the binary and nothing else, and that is only correct while the
        // binary needs nothing the base image lacks. A dependency arriving through a new library —
        // `libz` for a compression engine, `libcurl` for a client — changes this list, and today
        // nobody would learn that until a container failed to start with `cannot open shared object
        // file`, which names the library and nothing about the commit that added it.
        //
        // NOT A GATE, deliberately. A check that failed on a new entry would fail the pull request
        // that legitimately adds one, and the fix would be to update an expected list — a rubber
        // stamp within two sprints. What is wanted is visibility at the moment it changes: a line in
        // the log of the build that introduced the dependency, read by the person adding it.
        //
        // Never fails. `readelf` is binutils, and a Mac has neither it nor an ELF to point it at, so
        // the absence of an answer is reported as an absence rather than as a problem.
        val imageDir = layout.buildDirectory.dir("native-image")
        val binaryName = nativeService.baseName
        // WALKED RATHER THAN RESOLVED, because the staged path is not one path any more: a module
        // with two native targets stages `<konanTarget>/<baseName>` (#80), and a report that looked
        // only where a single-target build puts it would go silent exactly where there is more to
        // say.
        val stagedDepth = 2
        // Copied into a local for the same reason as the two above: reading `neededLine` from
        // inside the action would be a read of a property of the script.
        val neededPattern = neededLine
        doLast {
            val root = imageDir.get().asFile
            val staged =
                root
                    .walkTopDown()
                    .maxDepth(stagedDepth)
                    .filter { it.isFile && it.name == binaryName.get() }
                    .toList()

            // NOTHING STAGED IS ITS OWN ANSWER, not a silent success. Found while fixing #76: a
            // change to how the binary is selected staged it under `<target>/releaseExecutable/`
            // instead of flat, and everything downstream carried on — the report below was written
            // beside a file that was not there and said `readelf is not on PATH`, which is the
            // message for a completely different situation.
            //
            // Not an error, deliberately: a repository whose only native target is `linuxX64` has no
            // release executable to stage when `assemble` runs on a mac, and that is a legitimate
            // build rather than a defect.
            if (staged.isEmpty()) {
                logger.lifecycle(
                    "stageNativeImage: nothing staged — no release executable under " +
                        "build/bin/*/releaseExecutable/. Is a native target declared for this host?",
                )
                return@doLast
            }

            staged.forEach { binary ->
                val needed =
                    runCatching {
                        val process =
                            ProcessBuilder("readelf", "-d", binary.path)
                                .redirectErrorStream(true)
                                .start()
                        val text = process.inputStream.bufferedReader().readText()
                        process.waitFor()
                        text
                            .lineSequence()
                            .mapNotNull { neededPattern.find(it)?.groupValues?.get(1) }
                            .toList()
                    }.getOrNull()

                val line =
                    when {
                        needed == null -> "${binary.name}: readelf is not on PATH, so this is unchecked"
                        needed.isEmpty() -> "${binary.name} declares no shared libraries (not an ELF?)"
                        else -> "${binary.name} declares ${needed.size} — ${needed.joinToString(" ")}"
                    }
                logger.lifecycle("stageNativeImage: $line")

                val report = StringBuilder()
                report.appendLine("# What ${binary.name} asks the loader for. A change here is a change in")
                report.appendLine("# what the runtime image has to carry.")
                report.appendLine("#")
                report.appendLine("# NOT LISTED, AND NEVER WILL BE: ca-certificates. It is not a library, so readelf")
                report.appendLine("# cannot name it; without it every outbound TLS call fails with a message about a")
                report.appendLine("# certificate path and nothing about this file.")
                needed?.forEach(report::appendLine) ?: report.appendLine("# readelf unavailable")
                binary.resolveSibling(binary.name + ".needed.txt").writeText(report.toString())
            }
        }
    }

// THE SOURCES, AND THE LAYOUT THAT DEPENDS ON HOW MANY THERE ARE.
//
// One native target stages flat — `build/native-image/<baseName>` — which is the whole point of the
// task: a Dockerfile's `COPY` does not have to know whether the target was declared `linuxX64()` or
// `linuxX64("native")`.
//
// Two of them cannot both be that file. Until #80 they tried: every release `.kexe` was renamed to
// the same name in the same directory and Gradle refused with `Entry <name> is a duplicate`. A
// `duplicatesStrategy` would have been one line and the wrong one — it stages one of the two
// binaries, chosen arbitrarily, under a name that says nothing about which, and a `COPY` that finds
// the wrong file is worse than one that fails.
//
// So a module with several targets stages `<konanTarget>/<baseName>`, and the segment is the KONAN
// target (`linux_x64`), never the directory under `bin/` — that one carries the Kotlin target's
// name, which is the arbitrary thing this task exists to hide.
//
// AFTER EVALUATION, because "how many targets" is not answerable before the module has finished
// declaring them.
plugins.withId("org.jetbrains.kotlin.multiplatform") {
    afterEvaluate {
        val executables =
            extensions
                .getByType<KotlinMultiplatformExtension>()
                .targets
                .withType<KotlinNativeTarget>()
                .flatMap { target ->
                    target.binaries
                        .withType<Executable>()
                        .filter { it.buildType == NativeBuildType.RELEASE }
                        .map { target.konanTarget.name to it.linkTaskProvider }
                }
        val nested = executables.size > 1

        // THE PATH THE REFERENCE DOCKERFILE WILL NAME, decided here and not in the template.
        //
        // With one target it is the binary. With several, the image has to pick an architecture, and
        // the reference builder stage is pinned to `linux/amd64` — so `linux_x64` when it is among
        // the targets, and otherwise the first, which at least names something that exists rather
        // than a path nothing staged.
        if (nested) {
            val targets = executables.map { it.first }
            stagedPath.set(
                "${targets.firstOrNull { it == "linux_x64" } ?: targets.first()}/${nativeService.baseName.get()}",
            )
        }

        stageNativeImage.configure {
            executables.forEach { (konanTarget, linkTask) ->
                from(linkTask) {
                    include("*.kexe")
                    if (nested) into(konanTarget)
                }
            }
        }
    }
}

tasks.matching { it.name == "assemble" }.configureEach { dependsOn(stageNativeImage) }

// The reference two-stage Dockerfile, written out rather than generated on every build. What it
// carries that a fresh one would not: `ca-certificates` as its own line, because `ldd` cannot find it —
// it is not a library, and without it every outbound TLS call fails with a message about a certificate
// path; a builder and a runtime chosen as a PAIR by glibc, because a binary linked against the newer
// one will not start on the older; and the `~/.konan` cache mount, without which every image build
// downloads the Kotlin/Native toolchain again.
tasks.register("writeNativeDockerfile") {
    group = "distribution"
    description = "Writes sborka's reference Dockerfile for a Kotlin/Native service into this module"
    val target = layout.projectDirectory.file("Dockerfile").asFile
    val baseName = nativeService.baseName
    val staged = stagedPath
    val modulePath = project.path.removePrefix(":").replace(':', '/')
    doLast {
        check(!target.exists()) {
            "${target.path} already exists. This task writes a starting point, it does not merge: the " +
                "runtime image is where certificates, shared libraries and a base image's glibc are " +
                "decided, and overwriting those silently is how a working image becomes a broken one."
        }
        target.writeText(
            NativeImageReference.dockerfile(
                module = modulePath,
                binary = baseName.get(),
                stagedPath = staged.get(),
            ),
        )
        logger.lifecycle("wrote ${target.path}")
    }
}
