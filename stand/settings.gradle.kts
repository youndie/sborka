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

// AND AGAIN, OUTSIDE `pluginManagement`, WHICH IS NOT A DUPLICATE.
//
// The one above makes the conventions resolvable AS PLUGINS, by id through their markers. It does not
// substitute ordinary dependencies — and `sborka.lint` asks for one: `io.github.youndie.sborka:kapkan`
// on ktlint's `ktlintRuleset` configuration, because that is how a rule set jar is handed to ktlint.
//
// Without this line the stand went looking for the rule set on Maven Central and on the snapshot
// server, at the version this build has not published yet, and failed with "Could not find
// io.github.youndie.sborka:kapkan:0.1.0". Which is the stand doing its job: the composite the root
// build makes hid it, because there both halves are included at the top level, and CI runs the stand
// on its own — the way a repository does.
includeBuild("../build-logic")

// The settings plugin, applied the way a repository applies it. It brings the repositories, the
// `wip` catalog and the `.editorconfig` check with it — so a rename of any of those fails here.
plugins {
    id("io.github.youndie.sborka.settings")
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
include(":gradle-plugin")
