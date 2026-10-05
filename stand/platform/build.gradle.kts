plugins {
    `java-platform`
    id("io.github.youndie.sborka.publish")
}

// A platform registers no publication of its own, exactly like a `kotlin("jvm")` module and for a
// different reason. Here so that "publishes nothing, reports success" is caught for both shapes.

dependencies {
    constraints {
        api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    }
}

publishing {
    repositories {
        maven {
            name = "stand"
            url = uri(rootProject.layout.buildDirectory.dir("repo"))
        }
        // KAFKAKN'S SHAPE: a module that declared `local` before `sborka.publish` registered one, at
        // the same directory. The convention adopts it instead of adding a second — Gradle would not
        // have refused that, it would have renamed it `local2` and published twice into one tree.
        // The root's `verifyLocalRepository` finds this module there; the check below finds no twin.
        maven {
            name = "local"
            url = uri(rootProject.layout.buildDirectory.dir("local-repo"))
        }
    }
}

// Read after the conventions' own `afterEvaluate`, which was registered when the plugin applied.
afterEvaluate {
    val twins = publishing.repositories.names.filter { it.startsWith("local") && it != "local" }
    check(twins.isEmpty()) {
        "sborka.publish registered a second `local` beside the module's own, which Gradle renamed $twins: " +
            "two publish tasks now write one tree"
    }
}
