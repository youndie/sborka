package ru.workinprogress.sborka

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import ru.workinprogress.sborka.internal.SborkaSettings

// The Kotlin/JVM counterpart of `sborka.kmp`, and the same rule: it configures Kotlin, it does not
// apply it.

plugins {
    id("ru.workinprogress.sborka.base")
    id("ru.workinprogress.sborka.test")
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
            if (SborkaSettings.flag(project, "sborka.warningsAsErrors", default = true)) {
                allWarningsAsErrors.set(true)
            }
        }
    }

    // The same thing `sborka.kmp` puts in `commonTest`, so that a module moving between the two does
    // not lose its test framework on the way.
    dependencies.add("testImplementation", dependencies.kotlin("test"))
}
