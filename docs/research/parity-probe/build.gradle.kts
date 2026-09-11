plugins {
    kotlin("multiplatform") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

repositories { mavenCentral() }

// The versions are the portfolio's, read out of `catalog/sborka.versions.toml` rather than picked:
// a divergence found on a version nobody ships is a divergence nobody has.
kotlin {
    jvm()
    macosArm64()
    linuxX64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
        }
    }
}

// The transcript is the artefact, so it has to reach stdout whole and on every run. Without
// `--rerun`, an up-to-date test task prints nothing and the comparison silently compares one target
// against itself.
tasks.withType<org.gradle.api.tasks.testing.AbstractTestTask>().configureEach {
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
    }
    outputs.upToDateWhen { false }
}
