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

// The project conventions: everything applied to a module rather than to a build.

dependencies {
    api(projects.core)

    // APPLIED BY SBORKA, so it travels with it. Which ktlint runs is exactly the portfolio-wide
    // decision this repository exists to hold: the same catalog key `ktlint` means the TOOL version
    // 1.8.0 in nine repositories and the PLUGIN version 14.2.0 in four.
    implementation(libs.ktlint.gradle.plugin)

    // APPLIED BY SBORKA TOO, but only where `sborka.central` asks for it. On the classpath of every
    // consumer either way, which is the same trade ktlint makes above: a jar that is loaded and not
    // applied costs a classloader entry, while a consumer declaring the plugin itself would put the
    // version back into nineteen repositories -- and the version is the part Central's refusals are
    // about.
    implementation(libs.maven.publish.gradle.plugin)

    // NOT applied by sborka either, and for the reason stated for Kotlin below: `sborka.native-service`
    // configures the size gate, it does not choose razves' version. A module applies
    // `io.github.youndie.razves` itself at the version its own catalog names, and the convention reacts
    // with `plugins.withId`. Declared `compileOnly` so the extension type is on the compile classpath
    // and this jar carries no razves of its own - as `implementation` it would put razves' plugin
    // (47,731 bytes), its `core` (211,901) and kotlinx-serialization onto the build classpath of every
    // repository that takes any sborka convention, including the ones that ship no binary at all.
    compileOnly(libs.razves.gradle.plugin)

    // NOT applied by sborka, and that is the design. `sborka.kmp` configures Kotlin, it does not
    // choose its version: a module applies `kotlin("multiplatform")` itself, at whatever version its
    // own catalog names, and sborka reacts with `plugins.withId`. Declared `compileOnly` so the types
    // are on the compile classpath and this jar carries no Kotlin plugin of its own — otherwise every
    // Kotlin bump anywhere in the portfolio would wait on a sborka release.
    //
    // The arrangement that makes this work is the three-jar split in `settings.gradle.kts`: these
    // classes must NOT end up on a classloader that has no Kotlin plugin under it.
    compileOnly(libs.kotlin.gradle.plugin)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
