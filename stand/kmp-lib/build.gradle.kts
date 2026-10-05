plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("io.github.youndie.sborka.kmp")
    id("io.github.youndie.sborka.lint")
    id("io.github.youndie.sborka.publish")
}

// The targets are the module's, not the convention's — which is the point `sborka.kmp` is making by
// not declaring any. Two here: one JVM target, because the `org.gradle.jvm.version` attribute the
// publish convention adds only exists on a jvm variant, and one native target, because a target
// whose tests cannot run on the host is the case that made the convention leave target lists alone.
kotlin {
    jvm()
    linuxX64()

    // THE RELEASE TRAP (#121), switched on only by the CI step that asks for it.
    //
    // `src/releaseTrap` holds a test that passes on the debug test binary and fails on the release
    // one: mongkn M-91 in a few lines. Off by default because it is red by design wherever the
    // release run exists, and the stand's ordinary `check` has to stay green. The step in
    // `.github/workflows/check.yaml` runs it both ways: with `sborka.nativeReleaseTests=false` the
    // build must pass, which is the world before this convention; with the release run on it must
    // fail, and through `:kmp-lib:linuxX64ReleaseTest`.
    if (providers.gradleProperty("stand.releaseTrap").orNull == "true") {
        sourceSets.named("linuxX64Test") { kotlin.srcDir("src/releaseTrap/kotlin") }
    }
}

publishing {
    repositories {
        maven {
            name = "stand"
            url = uri(rootProject.layout.buildDirectory.dir("repo"))
        }
    }
}
