plugins {
    alias(libs.plugins.kotlinJvm)
    id("ru.workinprogress.sborka.jvm")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
    id("ru.workinprogress.sborka.mutation")
}

// A `kotlin("jvm")` module is the case that publishes NOTHING with a green exit code when the
// convention forgets to register a publication for it. It is first in the stand for that reason.

publishing {
    repositories {
        maven {
            name = "stand"
            url = uri(rootProject.layout.buildDirectory.dir("repo"))
        }
    }
}
