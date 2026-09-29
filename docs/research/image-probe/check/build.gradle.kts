import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    application
}

repositories { mavenCentral() }

dependencies {
    // Layers are tar streams with PAX and GNU long names; this is the reader that handles both.
    implementation("org.apache.commons:commons-compress:1.28.0")
    // The element API only — manifests and configs are read, never mapped to classes.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}

// Whatever JDK runs Gradle compiles it; the bytecode is 21 so that any current JDK runs it.
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
java { targetCompatibility = JavaVersion.VERSION_21 }

application {
    mainClass.set("check.MainKt")
    applicationName = "load-check"
}
