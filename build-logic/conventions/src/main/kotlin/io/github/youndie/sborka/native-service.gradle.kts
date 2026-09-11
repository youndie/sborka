package io.github.youndie.sborka

import io.github.youndie.sborka.internal.NativeImageReference
import io.github.youndie.sborka.internal.SborkaSettings
import io.github.youndie.sborka.internal.SizeGate
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

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
}

private val neededLine = Regex("Shared library: \\[(.+)]")

val nativeService = extensions.create<NativeServiceExtension>("nativeService")
nativeService.baseName.convention(project.name)

plugins.withId("org.jetbrains.kotlin.multiplatform") {
    extensions.configure<KotlinMultiplatformExtension> {
        targets.withType<KotlinNativeTarget>().configureEach {
            binaries.executable {
                entryPoint = nativeService.entryPoint.get()
                baseName = nativeService.baseName.get()
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
val stageNativeImage =
    tasks.register<Sync>("stageNativeImage") {
        group = "distribution"
        description = "Copies the release executable to build/native-image/ under a stable name"

        val binaries =
            project.provider {
                tasks
                    .matching { it.name.matches(Regex("^link(Release|Debug)?Executable.*")) }
                    .filter { it.name.contains("Release") }
            }
        dependsOn(binaries)

        from(
            project.provider {
                layout.buildDirectory
                    .dir("bin")
                    .get()
                    .asFile
                    .walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".kexe") && it.parentFile.name == "releaseExecutable" }
                    .toList()
            },
        )
        into(layout.buildDirectory.dir("native-image"))
        rename { nativeService.baseName.get() }

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
        doLast {
            val binary = imageDir.get().asFile.resolve(binaryName.get())
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
                        .mapNotNull { neededLine.find(it)?.groupValues?.get(1) }
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
            ),
        )
        logger.lifecycle("wrote ${target.path}")
    }
}
