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

    // THE CACHE TRAP, switched on only by the CI step that asks for it.
    //
    // `src/cacheTrap` holds a test that fails every time. Under `allTests` KGP lets `jvmTest` finish
    // green and fails the aggregate instead, so without `sborka.kmp` the failed run's outputs go into
    // the build cache and the next fresh checkout loads `jvmTest` FROM-CACHE and reports BUILD
    // SUCCESSFUL. The step in `.github/workflows/check.yaml` builds it twice around a `clean`: with the
    // guard on, both builds must be red and the second must have run the test; with
    // `sborka.testCacheGuard=false` the second must be green from the cache, which is the defect.
    if (providers.gradleProperty("stand.cacheTrap").orNull == "true") {
        sourceSets.named("jvmTest") { kotlin.srcDir("src/cacheTrap/kotlin") }
    }
}

// THE TRAP'S CACHE KEY IS THIS RUN'S. CI restores the build cache, and an entry the control stored on an
// earlier run would otherwise be loaded by this run's first build and turn the red it must see green.
// So the step names its run, and a replay it sees can only be one it stored itself.
if (providers.gradleProperty("stand.cacheTrap").orNull == "true") {
    tasks.named("jvmTest") {
        inputs.property("stand.cacheTrapRun", providers.gradleProperty("stand.cacheTrapRun").orElse("local"))
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
