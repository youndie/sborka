// The load check (B-37): can a base image's loader load a native binary, answered from the image's
// files before anything is pushed. Moved here from `docs/research/image-probe/check/`, where B-30
// scored it against `docker run`; the corpus that did that now runs in CI against this code.

plugins {
    // `embedded-kotlin`, like `:core`: a library, no plugin descriptors to announce.
    `embedded-kotlin`
    alias(libs.plugins.ktlint)
    // A command line as well as a library: `docs/research/image-probe/score.sh` runs the corpus
    // through it, which is how the CI job compares this code with `docker run` row by row.
    application
}

ktlint {
    version.set(libs.versions.ktlintTool)
    filter { exclude { it.file.path.contains("/build/generated/") } }
}

dependencies {
    // Layers are tar streams with PAX headers and GNU long names; this is the reader that handles both.
    implementation(libs.commons.compress)
    // Manifests and image configs, read as trees and never mapped to classes.
    implementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

application {
    mainClass.set("io.github.youndie.sborka.image.LoadCheckCliKt")
    applicationName = "load-check"
}

// THE KOTLIN STANDARD LIBRARY, FOR THE COMMAND ONLY. `embedded-kotlin` makes it `compileOnly`, which is
// right for everything a build loads — Gradle brings its own — and wrong for a `main` started by
// `java`: the first corpus run of the moved check failed every row with
// `NoClassDefFoundError: kotlin/jvm/internal/Intrinsics`. Added to the distribution and its start
// script, not to the published module's dependencies, so a consumer's POM does not change.
val cliRuntime by configurations.creating
dependencies { cliRuntime(embeddedKotlin("stdlib")) }
tasks.named<CreateStartScripts>("startScripts") { classpath = classpath!! + cliRuntime }
distributions.named("main") { contents { from(cliRuntime) { into("lib") } } }
