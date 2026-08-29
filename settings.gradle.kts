// sborka builds itself with the plainest settings it has: the conventions it publishes are for other
// repositories, and a build that applied its own unpublished plugins to itself could not say which
// version of them it was testing.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "sborka"

// A build of its own rather than a subproject, so that `stand` can include it the same way and ask
// for the plugins BY ID. A subproject would be on this build's classpath already, which is a
// classpath no consumer has.
includeBuild("build-logic")

include(":catalog")

// THE PROOF, and it is a separate build on purpose.
//
// A convention plugin exercised from inside the build that defines it is exercised through a
// classpath no consumer has. `stand` resolves the plugins the way a consumer does — by id, through
// `includeBuild` — so an id derived from the wrong package, a marker that is not generated, and a
// runtime dependency that was declared `compileOnly` all fail here rather than in the first
// repository to migrate.
includeBuild("stand")
