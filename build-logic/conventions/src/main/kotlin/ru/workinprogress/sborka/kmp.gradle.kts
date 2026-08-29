package ru.workinprogress.sborka

import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
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

        sourceSets.named("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
