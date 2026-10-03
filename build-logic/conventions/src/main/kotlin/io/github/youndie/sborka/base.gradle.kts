package io.github.youndie.sborka

import io.github.youndie.sborka.internal.SborkaSettings
import io.github.youndie.sborka.internal.WebLinkSlots
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink

// What every module of every repository has regardless of its platform: a coordinate, a version and
// a toolchain.
//
// It is a convention plugin rather than a `subprojects { }` block in a root script, and the reason
// is not tidiness. A module built on a lower toolchain than its dependencies fails with a message
// naming the DEPENDENCY, so the toolchain has to be impossible to forget; and a group repeated per
// module is how six modules of one repository got published under a group derived from a directory
// name.
//
// It applies no Kotlin plugin and requires none. Everything below reacts to what the module itself
// declared — so a module on `kotlin("jvm")`, a module on `kotlin("multiplatform")` and a
// `java-platform` can all take this plugin and get the part of it that applies to them.

group = SborkaSettings.group(project)
version = SborkaSettings.version(project)

val toolchain = SborkaSettings.jvmToolchain(project)

plugins.withId("org.jetbrains.kotlin.jvm") {
    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(toolchain)
    }
}

plugins.withId("org.jetbrains.kotlin.multiplatform") {
    extensions.configure<KotlinMultiplatformExtension> {
        jvmToolchain(toolchain)
    }
}

// ONE KOTLIN/WASM OR KOTLIN/JS LINK AT A TIME, IN THE WHOLE BUILD (sborka#132).
//
// Every `compile*ExecutableKotlinWasmJs` and `compile*ExecutableKotlinJs` is a `KotlinJsIrLink`, and
// every one of them runs inside the build's Kotlin daemon, so links that Gradle starts together share
// one heap. A Compose link holds 0.7-0.9 GB of live data. Three of them filled an inherited `-Xmx3g`
// in one repository, which then failed cold builds with "GC overhead limit exceeded"; eight filled
// the same 3 GB in another, which spent an hour in back-to-back full collections before failing the
// same way. Warm builds take the links from the cache and never show it, so it surfaces as a red that
// a re-run turns green.
//
// The two repositories fixed it two ways. One raised the daemon's heap to a measured 5 GB; the other
// queued its links through a build service with one slot, kept 3 GB, and peaked at about 2 GB. The
// queue is what lives here, because it holds however many modules link and on whatever machine
// builds them, while a heap number has to be measured again for every module that starts linking.
// What it costs is in docs/conventions.md: nothing measurable on a four-core hosted runner with seven
// links, about a minute of a four-to-five-minute build on a twenty-core box with eight.
//
// BY TASK TYPE, so a module that starts linking tomorrow is queued without anybody naming it. JS AND
// WASM BOTH: the type is shared, and so is the mechanism — a whole program lowered inside the same
// daemon — and a js link beside a wasm link shares the heap as surely as two wasm links do.
//
// REGISTERED BY THE FIRST LINK, not by every module: a build without a js or wasm executable gets no
// service at all, and a module without one has no task that asks for it.
//
// `sborka.serializeWebLinks=false` gives the parallelism back, for a repository that measured its
// daemon's heap and would rather have the minutes.
plugins.withId("org.jetbrains.kotlin.multiplatform") {
    if (SborkaSettings.flag(project, WebLinkSlots.PROPERTY, default = true)) {
        tasks.withType(KotlinJsIrLink::class.java).configureEach {
            usesService(
                gradle.sharedServices.registerIfAbsent(WebLinkSlots.NAME, WebLinkSlots::class.java) {
                    maxParallelUsages.set(1)
                },
            )
        }
    }
}
