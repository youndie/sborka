package io.github.youndie.sborka

import io.github.youndie.sborka.internal.CinteropLink
import io.github.youndie.sborka.internal.KspMetadataWiring
import io.github.youndie.sborka.internal.SborkaSettings
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.Executable
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetWithHostTests
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.gradle.tasks.CInteropProcess
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.HostManager

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
                // Against the floor's class library as well, not only its class file version: see
                // `sborka.jvm`, which says why and where this came from.
                freeCompilerArgs.add("-Xjdk-release=$floor")
            }
        }

        sourceSets.named("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// THE SUITE AGAIN, ON THE BINARY THAT SHIPS (#121).
//
// KGP registers one test binary per native target, in DEBUG, and one task to run it. What ships is
// the release binary — the service's image, the binary a library's consumer links — and the two are
// not the same program: a release build leaves out the checks on casts out of a generic type
// (`genericSafeCasts` is off there), so a `catch (e: ClassCastException)` that is reached in debug
// is never reached in release, and the value goes on into code that was written for another type.
// That is mongkn M-91: one request, 200 from the debug binary and 500 from the release one, with the
// consumer's suite green. The stand holds it in a few lines (`stand/kmp-lib/src/releaseTrap`).
//
// So the same suite runs a second time, linked in release: a test binary and a test run for it,
// because the binary alone is not enough — no task runs it until a run is created for it — and
// `check` waits for that run.
//
// ONLY THE TARGET THIS HOST RUNS. A release link is the slow kind, and on a host that cannot execute
// the binary it would be paid for nothing: a Mac does not run `linuxX64` and a Linux runner does not
// run `macosArm64`. `macosX64` under Rosetta is left out as well; its debug run is still there.
//
// IN `afterEvaluate`, AND ONLY WHERE THE MODULE DID NOT DO IT ITSELF. Three repositories wrote this
// by hand before it was a convention, and KGP refuses a second test binary of the same build type:
// done in `configureEach`, it would come first and break every one of those scripts at configuration.
//
// OFF BY DEFAULT, `sborka.nativeReleaseTests=true` to opt in, and that was measured rather than
// guessed: the release link is 6–13 times the debug one (about two minutes on mani's server), an
// integration-heavy suite runs twice (kafkakn: nine more minutes), and kafkakn's release run is red
// where its debug run is green — on by default, its next sborka bump would have gone red unasked.
// The numbers and where they came from: docs/conventions.md, `sborka.kmp`.
plugins.withId("org.jetbrains.kotlin.multiplatform") {
    if (SborkaSettings.flag(project, "sborka.nativeReleaseTests", default = false)) {
        afterEvaluate {
            extensions
                .getByType<KotlinMultiplatformExtension>()
                .targets
                .withType<KotlinNativeTargetWithHostTests>()
                .filter { it.konanTarget == HostManager.host }
                .forEach { target ->
                    if (target.binaries.findTest(NativeBuildType.RELEASE) == null) {
                        target.binaries.test(listOf(NativeBuildType.RELEASE))
                    }
                    if (target.testRuns.findByName("release") == null) {
                        target.testRuns.create("release") {
                            setExecutionSourceFrom(target.binaries.getTest(NativeBuildType.RELEASE))
                        }
                    }
                    // SAID OUT LOUD, although KGP's `allTests` already picks the run up: `check`
                    // reaching it through an aggregate is a property of KGP's version, not of this
                    // convention. BY NAME, because the run's public type does not expose its task;
                    // KGP names the task of a run other than the default `<target><Run>Test`.
                    tasks.named("check") { dependsOn("${target.name}ReleaseTest") }
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

// A CINTEROP KLIB CARRIES ITS OWN LINK, OR THE BUILD SAYS SO (sborka#116).
//
// A library with a binding was published twice in this portfolio in a state where everything inside
// its own build was green and no consumer could link it. Both times the same way: the archives and
// `-l` options sat on the library's OWN binaries, through `binaries.all { linkerOpts(…) }`. Its tests
// linked, `check` passed, the publish succeeded, and the published `…-cinterop-<name>.klib` carried
// the bindings and nothing they bind to. The first build outside the repository failed with
// `undefined symbol` on every C function (kafkakn B-15: nine of them; smtpkn M-110). Both were found by
// a consumer, not by the build that made the artefact.
//
// TWO HALVES.
//
// REFUSED AT CONFIGURATION: a module that declares a cinterop and names a library (`-l<x>`, `-L<dir>`,
// `-framework`, a path to `*.a` / `*.so`) on any of its own binaries, or in `cinterops { linkerOpts }`,
// which cinterop drops with one warning line. With nothing on its own binaries the library's suite links
// the way a stranger's build does, so its own tests become the consumer check. Options that name no
// library stay allowed: `-Wl,--as-needed` above is sborka's own.
//
// READ FROM THE ARTEFACT IN `check`: `verifyCinteropKlibs` opens every klib the main compilations'
// cinterops produce, prints what each carries, and fails on a `.def` that declares headers whose klib
// carries neither an archive nor any library — the B-15 / M-110 signature. The archives are read from
// `included/`, not from the manifest: `extraOpts("-staticLibrary", …)` puts the archive there and writes
// no manifest key for it, and a manifest reader would call that good klib empty.
//
// In `afterEvaluate` because the cinterops and the binaries are declared in the module's own script,
// below the `plugins` block that applies this one.
plugins.withId("org.jetbrains.kotlin.multiplatform") {
    afterEvaluate {
        val nativeTargets = extensions.getByType<KotlinMultiplatformExtension>().targets.withType<KotlinNativeTarget>()
        val interops =
            nativeTargets.flatMap { target ->
                target.compilations.flatMap { compilation ->
                    compilation.cinterops.map { Triple(target, compilation, it) }
                }
            }
        if (interops.isEmpty()) {
            return@afterEvaluate
        }

        val misplaced =
            nativeTargets.flatMap { target ->
                target.binaries.flatMap { binary ->
                    val named = CinteropLink.librariesNamed(binary.linkerOpts)
                    named.map { "binary '${binary.name}' of ${target.name}: $it" }
                }
            } +
                interops.flatMap { (target, _, interop) ->
                    CinteropLink.librariesNamed(interop.linkerOpts).map {
                        "cinterops { linkerOpts } of '${interop.name}' on ${target.name}, which cinterop drops: $it"
                    }
                }
        if (misplaced.isNotEmpty()) {
            val definitions =
                interops
                    .mapNotNull { (_, _, interop) ->
                        interop.definitionFile.orNull
                            ?.asFile
                            ?.relativeTo(projectDir)
                            ?.path
                    }.distinct()
                    .ifEmpty { listOf("src/nativeInterop/cinterop/<name>.def") }
            throw GradleException(
                "$path declares a cinterop, and names a library to link where a consumer never sees it:\n" +
                    misplaced.joinToString("\n") { "- $it" } + "\n" +
                    "Options on this module's own binaries reach only its own binaries: its tests link, the klib it " +
                    "publishes carries nothing, and a consumer's link fails with `undefined symbol` on every C " +
                    "function (kafkakn B-15, smtpkn M-110). Move them into the .def the klib is built from " +
                    "(${definitions.joinToString()}): archives as `staticLibraries = libfoo.a`, their directory " +
                    "from Gradle as `extraOpts(\"-libraryPath\", dir)`; a shared library as " +
                    "`linkerOpts = -L<dir> -lfoo`. Options that name no library, such as -Wl,--as-needed, may stay.",
            )
        }

        val published = interops.filter { (_, compilation, _) -> compilation.name == "main" }
        if (published.isEmpty()) {
            return@afterEvaluate
        }
        val verify =
            tasks.register<VerifyCinteropKlibs>("verifyCinteropKlibs") {
                group = "verification"
                description =
                    "Prints what each cinterop klib carries for a consumer's link, and fails on one that carries nothing"
                published.forEach { (target, _, interop) ->
                    val processing = tasks.named<CInteropProcess>(interop.interopProcessingTaskName)
                    klibs.add(
                        objects.newInstance<CinteropKlib>().apply {
                            label.set("${target.name} ${interop.name}")
                            konanTarget.set(target.konanTarget.name)
                            val family = target.konanTarget.family
                            this.family.set(family.name.lowercase())
                            klib.from(processing.flatMap { it.outputFileProvider }).builtBy(processing)
                            definitionFile.set(interop.definitionFile)
                            gradleHeaders.from(interop.headers)
                        },
                    )
                }
            }
        tasks.named("check") { dependsOn(verify) }
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
            val handWired = providers.gradleProperty(KspMetadataWiring.HAND_WIRED_PROPERTY).orNull
            if (KspMetadataWiring.handWired(handWired, path)) {
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
                    "per-target tasks those processors need. Wire this module by hand and name it in " +
                    "${KspMetadataWiring.HAND_WIRED_PROPERTY} in the root gradle.properties."
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
