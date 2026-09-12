plugins { kotlin("multiplatform") version "2.4.10" }
repositories { mavenCentral() }
kotlin {
    jvm()
    macosArm64()
    linuxX64()
    sourceSets.commonTest.dependencies {
        implementation(kotlin("test"))
        implementation("io.ktor:ktor-network:3.5.2")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    }
}
tasks.withType<org.gradle.api.tasks.testing.AbstractTestTask>().configureEach {
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}
