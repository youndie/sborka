plugins {
    `kotlin-dsl`
    // SBORKA IS LINTED BY THE FORMATTER SBORKA SHIPS, at the version `:core` generates from the same
    // catalog entry `sborka.lint` hands to consumers. A repository that ships a linter and does not
    // run it is the shape of defect its own checks exist to catch.
    alias(libs.plugins.ktlint)
}

ktlint {
    version.set(libs.versions.ktlintTool)
    filter { exclude { it.file.path.contains("/build/generated/") } }
}

// The settings conventions, and NOTHING that touches Kotlin.
//
// A settings plugin is loaded by the settings classloader, which is the parent of every project
// buildscript classloader and never carries the Kotlin Gradle plugin. Anything here that referenced a
// Kotlin type would work while compiling and fail at apply time — and, because a shared jar means a
// shared classloader, would take the project conventions down with it. See `../settings.gradle.kts`.

dependencies {
    api(projects.core)
}
