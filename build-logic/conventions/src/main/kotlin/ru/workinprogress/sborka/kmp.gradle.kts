package ru.workinprogress.sborka

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import ru.workinprogress.sborka.internal.SborkaSettings

// The mechanics of a multiplatform library — AND DELIBERATELY NOT ITS TARGETS.
//
// The portfolio declares 243 targets across fourteen repositories, which looks like the biggest
// duplication of all until the comments beside them are read: one repository leaves out `macosX64`
// because Kotlin has deprecated it and nothing has ever run there; its neighbour keeps it because its
// milestones are closed against it; a third is capped at what its Telegram dependency publishes; a
// fourth leaves out the iOS simulator because a test task that cannot run is worse than an absent
// target — it looks like coverage.
//
// Those are four different decisions with four different reasons, and a convention that made them one
// would not be removing duplication, it would be deleting four arguments. So: this plugin gives what
// every KMP library here agrees on and leaves the target list where it was argued.
//
// It does not APPLY the Kotlin plugin either. The module applies `kotlin("multiplatform")` at whatever
// version its own catalog names; sborka reacts. Otherwise every Kotlin bump anywhere in the portfolio
// would have to wait on a sborka release.

plugins {
    id("ru.workinprogress.sborka.base")
    id("ru.workinprogress.sborka.test")
}

val floor = SborkaSettings.jvmFloor(project)

plugins.withId("org.jetbrains.kotlin.multiplatform") {
    extensions.configure<KotlinMultiplatformExtension> {
        // A LIBRARY: every public declaration spells out its visibility and its return type. Off by
        // default for an application, which has no consumers to spell them out for.
        if (SborkaSettings.flag(project, "sborka.explicitApi", default = true)) {
            explicitApi()
        }

        if (SborkaSettings.flag(project, "sborka.warningsAsErrors", default = true)) {
            compilerOptions {
                allWarningsAsErrors.set(true)
            }
        }

        // THE BYTECODE MATCHES THE FLOOR THE METADATA CLAIMS.
        //
        // `sborka.publish` stamps `org.gradle.jvm.version` from `sborka.jvmFloor`, and until this
        // block existed nothing made the bytecode agree with it: a module on a toolchain of 25 and a
        // floor of 17 published class files needing Java 25 under metadata promising 17. Gradle then
        // lets the consumer through — the attribute says they are welcome — and the failure arrives
        // at class loading as UnsupportedClassVersionError, naming a class file version and nothing
        // about this library. Every machine that builds it is too new to see it.
        //
        // Deliberately far below the toolchain in the general case: the JDK that builds a library is
        // not the JDK that has to run it.
        targets.withType<KotlinJvmTarget>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.fromTarget(floor.toString()))
            }
        }

        sourceSets.named("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
