// A consumer of sborka, standing in for the nineteen real ones.
//
// It asks for the plugins BY ID through `includeBuild`, which is how a repository will ask for them
// by id through a published marker: the same resolution path, without a publish in the middle.

pluginManagement {
    includeBuild("../build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// The settings plugin, applied the way a repository applies it. It brings the repositories, the
// `wip` catalog and the `.editorconfig` check with it — so a rename of any of those fails here.
plugins {
    id("ru.workinprogress.sborka.settings")
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "stand"

include(":jvm-lib")
include(":kmp-lib")
include(":platform")
