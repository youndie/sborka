package ru.workinprogress.sborka

import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import ru.workinprogress.sborka.internal.SborkaSettings

// The Kotlin/JVM counterpart of `sborka.kmp`, and the same rule: it configures Kotlin, it does not
// apply it.

plugins {
    id("ru.workinprogress.sborka.base")
    id("ru.workinprogress.sborka.test")
}

plugins.withId("org.jetbrains.kotlin.jvm") {
    extensions.configure<KotlinJvmProjectExtension> {
        if (SborkaSettings.flag(project, "sborka.explicitApi", default = true)) {
            explicitApi()
        }

        compilerOptions {
            if (SborkaSettings.flag(project, "sborka.warningsAsErrors", default = true)) {
                allWarningsAsErrors.set(true)
            }
        }
    }

    // The same thing `sborka.kmp` puts in `commonTest`, so that a module moving between the two does
    // not lose its test framework on the way.
    dependencies.add("testImplementation", dependencies.kotlin("test"))
}
