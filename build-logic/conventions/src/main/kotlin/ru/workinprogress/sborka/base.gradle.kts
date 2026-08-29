package ru.workinprogress.sborka

import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import ru.workinprogress.sborka.internal.SborkaSettings

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
