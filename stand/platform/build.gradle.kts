plugins {
    `java-platform`
    id("ru.workinprogress.sborka.publish")
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
    }
}
