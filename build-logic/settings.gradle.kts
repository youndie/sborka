// The conventions are a build of their own so that `stand` can include them and ask for the plugins
// by id, the way a consuming repository does.

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }

    versionCatalogs {
        // The same catalog the root build reads. Shared by path rather than copied: two files naming
        // the ktlint this repository runs on itself is one file too many.
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// THREE JARS, AND THE SPLIT IS NOT COSMETIC.
//
// Gradle keys a plugin classloader on its classpath. A settings plugin and a project plugin shipped
// in ONE jar therefore share a loader — the settings one, which is the parent — and that loader has no
// Kotlin Gradle plugin on it, because the settings classpath never does. Every `compileOnly` reference
// to a Kotlin type in the project conventions then fails at apply time with
// `NoClassDefFoundError: org/jetbrains/kotlin/gradle/dsl/KotlinJvmProjectExtension`, naming a class and
// not the arrangement that lost it.
//
// It was one jar until the stand applied both halves at once and produced exactly that, which is what
// the stand is for: nothing about it is visible from compiling the plugins.
include(":core")
include(":conventions")
include(":settings")
