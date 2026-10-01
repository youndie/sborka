package io.github.youndie.sborka

import io.github.youndie.sborka.internal.SborkaSettings
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

// The Kotlin/JVM counterpart of `sborka.kmp`, and the same rule: it configures Kotlin, it does not
// apply it.

plugins {
    id("io.github.youndie.sborka.base")
    id("io.github.youndie.sborka.test")
}

val floor = SborkaSettings.jvmFloor(project)

// THE BYTECODE MATCHES THE FLOOR THE METADATA CLAIMS. A plain Kotlin/JVM module derives
// `org.gradle.jvm.version` from its TOOLCHAIN, so a module on 25 with a floor of 17 advertised 25 and
// was at least honest; setting the target makes the two the same number, taken from the same line of
// `gradle.properties`. `options.release` goes on the Java side of the same module, or the Kotlin and
// Java halves disagree and Gradle says so at configuration time.
plugins.withId("java") {
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(floor)
    }
}

plugins.withId("org.jetbrains.kotlin.jvm") {
    extensions.configure<KotlinJvmProjectExtension> {
        if (SborkaSettings.flag(project, "sborka.explicitApi", default = true)) {
            explicitApi()
        }

        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(floor.toString()))
            // AND THE API MATCHES IT TOO. `jvmTarget` alone lowers the class file version and still
            // compiles against the TOOLCHAIN's class library, so with a toolchain of 25 and a floor of
            // 17 a call into something added in 21 compiles, publishes, and fails on a 17 runtime with
            // NoSuchMethodError. `-Xjdk-release` compiles against the floor's API, the Kotlin
            // counterpart of `options.release` on the Java side above. kompot held its whole toolchain
            // at 17 to get this check; with it here, the toolchain is free to be the newest JDK.
            freeCompilerArgs.add("-Xjdk-release=$floor")
            if (SborkaSettings.flag(project, "sborka.warningsAsErrors", default = true)) {
                allWarningsAsErrors.set(true)
            }
        }
    }

    // The same thing `sborka.kmp` puts in `commonTest`, so that a module moving between the two does
    // not lose its test framework on the way.
    dependencies.add("testImplementation", dependencies.kotlin("test"))
}
