package ru.workinprogress.sborka

import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import ru.workinprogress.sborka.internal.NativeImageReference

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
    id("ru.workinprogress.sborka.base")
}

/** How the executable is named and where it starts. */
interface NativeServiceExtension {
    /** The fully qualified `main`. Kotlin/Native has no manifest to find it in. */
    val entryPoint: Property<String>

    /** The file name, without `.kexe`. Defaults to the module name. */
    val baseName: Property<String>
}

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
