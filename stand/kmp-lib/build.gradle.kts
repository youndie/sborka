plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
}

// The targets are the module's, not the convention's — which is the point `sborka.kmp` is making by
// not declaring any. Two here: one JVM target, because the `org.gradle.jvm.version` attribute the
// publish convention adds only exists on a jvm variant, and one native target, because a target
// whose tests cannot run on the host is the case that made the convention leave target lists alone.
kotlin {
    jvm()
    linuxX64()
}

publishing {
    repositories {
        maven {
            name = "stand"
            url = uri(rootProject.layout.buildDirectory.dir("repo"))
        }
    }
}
