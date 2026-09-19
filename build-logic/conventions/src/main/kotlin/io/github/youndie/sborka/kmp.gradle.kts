package io.github.youndie.sborka

import io.github.youndie.sborka.internal.KspMetadataWiring
import io.github.youndie.sborka.internal.SborkaSettings
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.Executable
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.konan.target.Family

// The mechanics of a multiplatform library — AND DELIBERATELY NOT ITS TARGETS.
//
// The portfolio declares 243 targets across fourteen repositories, which looks like the biggest
// duplication of all until the comments beside them are read: one repository leaves out `macosX64`
// because Kotlin has deprecated it and nothing has ever run there; its neighbour keeps it because its
// milestones are closed against it; a third is capped at what its Telegram dependency publishes; a
// fourth leaves out the iOS simulator because a test task that cannot run is worse than an absent
// target — it looks like coverage.
//
// Those are four different decisions with four different reasons, and a convention that made them one
// would not be removing duplication, it would be deleting four arguments. So: this plugin gives what
// every KMP library here agrees on and leaves the target list where it was argued.
//
// It does not APPLY the Kotlin plugin either. The module applies `kotlin("multiplatform")` at whatever
// version its own catalog names; sborka reacts. Otherwise every Kotlin bump anywhere in the portfolio
// would have to wait on a sborka release.

plugins {
    id("io.github.youndie.sborka.base")
    id("io.github.youndie.sborka.test")
}

val floor = SborkaSettings.jvmFloor(project)

plugins.withId("org.jetbrains.kotlin.multiplatform") {
    extensions.configure<KotlinMultiplatformExtension> {
        // A LIBRARY: every public declaration spells out its visibility and its return type. Off by
        // default for an application, which has no consumers to spell them out for.
        if (SborkaSettings.flag(project, "sborka.explicitApi", default = true)) {
            explicitApi()
        }

        if (SborkaSettings.flag(project, "sborka.warningsAsErrors", default = true)) {
            compilerOptions {
                allWarningsAsErrors.set(true)
            }
        }

        // A LINUX EXECUTABLE DECLARES ONLY WHAT IT USES.
        //
        // A Kotlin/Native binary lists ten shared libraries and imports symbols from three. The other
        // seven come with `platform.posix`, whose klib manifest carries
        // `-lresolv -lm -lpthread -lutil -lcrypt -lrt` for every program on Linux whether or not
        // anything calls them — measured identically on two servers, a CLI and a hello-world in
        // `docs/research/research-static-binary.md` §1.2.
        //
        // One of the seven is `libcrypt.so.1`, which `gcr.io/distroless/cc` does not carry. So an
        // image either copies it out of the builder by hand — which two repositories here do — or the
        // container exits before it logs, with `cannot open shared object file`. And the copy brings
        // its own hazard: the file is glibc-version-coupled, so the builder image must then be no
        // newer than the runtime, and when it is not the failure reads `GLIBC_2.38 not found`.
        //
        // `--as-needed` lets the linker drop what nothing referenced. Seven NEEDED entries instead of
        // ten, no copy, and no pairing rule to get wrong.
        //
        // LINUX ONLY, and that is not caution: `ld64` and `lld-link` do not take this flag, so an
        // ungated version would fail every Apple and mingw link in the portfolio.
        targets
            .withType<KotlinNativeTarget>()
            .matching { it.konanTarget.family == Family.LINUX }
            .configureEach {
                binaries.withType<Executable>().configureEach {
                    linkerOpts("-Wl,--as-needed")
                }
            }

        // THE BYTECODE MATCHES THE FLOOR THE METADATA CLAIMS.
        //
        // `sborka.publish` stamps `org.gradle.jvm.version` from `sborka.jvmFloor`, and until this
        // block existed nothing made the bytecode agree with it: a module on a toolchain of 25 and a
        // floor of 17 published class files needing Java 25 under metadata promising 17. Gradle then
        // lets the consumer through — the attribute says they are welcome — and the failure arrives
        // at class loading as UnsupportedClassVersionError, naming a class file version and nothing
        // about this library. Every machine that builds it is too new to see it.
        //
        // Deliberately far below the toolchain in the general case: the JDK that builds a library is
        // not the JDK that has to run it.
        targets.withType<KotlinJvmTarget>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.fromTarget(floor.toString()))
            }
        }

        sourceSets.named("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// AND THE SAME FLOOR SAID OUT LOUD IN THE METADATA.
//
// A plain `kotlin("jvm")` module gets this for free — the java plugin derives
// `org.gradle.jvm.version` from its compilation — but a Kotlin Multiplatform module publishes its jvm
// variants with no such attribute at all, and those are the ones consumers actually take. Gradle then
// has nothing to refuse a too-old consumer with: resolution succeeds, compilation succeeds, and the
// failure arrives at class loading as UnsupportedClassVersionError, naming a bytecode version rather
// than this library.
//
// Set HERE rather than in `sborka.publish`, beside the `jvmTarget` above, because the two are one
// statement made twice. Split across two plugins they came apart: a repository that takes
// `sborka.kmp` and publishes through vanniktech to Maven Central compiled to the floor and
// advertised nothing.
plugins.withId("org.jetbrains.kotlin.multiplatform") {
    afterEvaluate {
        // FOUND BY WHAT A CONFIGURATION IS RATHER THAN BY WHAT IT IS CALLED. The obvious version of
        // this named `jvmApiElements` and `jvmRuntimeElements`, which covers a `jvm()` target and
        // misses `jvm("desktop")` entirely. A configuration's name comes from its target, so a name is
        // not a property of the thing being looked for; the java-api/java-runtime usage is, and only a
        // jvm target carries it — every other target of a multiplatform module publishes kotlin-api.
        configurations
            .filter { configuration ->
                configuration.isCanBeConsumed &&
                    configuration.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name == Category.LIBRARY &&
                    configuration.attributes
                        .getAttribute(Usage.USAGE_ATTRIBUTE)
                        ?.name in setOf(Usage.JAVA_API, Usage.JAVA_RUNTIME)
            }.forEach { configuration ->
                configuration.attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, floor)
            }
    }
}

// A PROCESSOR THAT RUNS ONCE, OVER COMMON METADATA, WIRED IN ONE PLACE.
//
// Saying "run KSP over commonMain and let everybody wait for it" takes three paragraphs of build
// script, and the portfolio had them byte-identical in seven modules of one repository. The cost is
// on record rather than assumed: a race between KSP and dokka over the same generated directory had
// to be fixed with one edit in seven files, and missing any one of them would have left the flake
// alive — the eighth copy, in the module written after the fix, would have missed it by default.
//
// WHAT STAYS IN THE MODULE: the processor itself and whatever arguments it takes. Those differ per
// module and are not a convention.
//
// THE GATE IS THE PROCESSOR, NOT THE PLUGIN. A module can apply KSP for something else entirely —
// one in kompot runs a screenshot processor over `desktopTest` and nothing over commonMain — and for
// that module every line below is wrong: an empty source directory, and, worse, its per-target task
// switched off. So the question asked is not "is KSP applied" but "did this module put a processor
// on `kspCommonMainMetadata`", which is a fact about the module rather than a flag somebody sets.
//
// It is asked in `afterEvaluate` because that is when the answer exists: the dependency is declared
// in the module's own script, below the `plugins` block that applies this one. Late is safe for what
// is done with it — `configureEach` is lazier still, and a source directory is read when a compile
// task resolves its inputs — but it is NOT safe for adding dependencies, so nothing here does.
plugins.withId("org.jetbrains.kotlin.multiplatform") {
    plugins.withId("com.google.devtools.ksp") {
        afterEvaluate {
            val processors = configurations.findByName(KspMetadataWiring.PROCESSOR_CONFIGURATION)
            if (processors == null || processors.dependencies.isEmpty()) {
                return@afterEvaluate
            }

            // LOUD RATHER THAN HALF-WIRED. Switching off the per-target KSP tasks is right exactly
            // while there is nothing for them to do. A module that processes common metadata AND a
            // platform source set would lose the second half in silence — no generated code, and
            // tests that compile against nothing and pass.
            val elsewhere =
                KspMetadataWiring.processorsOutsideCommonMetadata(
                    configurations.filter { it.dependencies.isNotEmpty() }.map { it.name },
                )
            require(elsewhere.isEmpty()) {
                "$path declares KSP processors on $elsewhere beside ${KspMetadataWiring.PROCESSOR_CONFIGURATION}. " +
                    "sborka.kmp wires the common-metadata case only, and wiring it here would switch off the " +
                    "per-target tasks those processors need. Wire this module by hand."
            }

            extensions.configure<KotlinMultiplatformExtension> {
                sourceSets.named("commonMain") {
                    kotlin.srcDir(KspMetadataWiring.GENERATED_SOURCES)
                }
            }

            tasks.matching { KspMetadataWiring.readsGeneratedSources(it.name) }.configureEach {
                dependsOn(KspMetadataWiring.GENERATOR)
            }

            tasks.matching { KspMetadataWiring.disabledAsRedundant(it.name) }.configureEach {
                enabled = false
            }
        }
    }
}
