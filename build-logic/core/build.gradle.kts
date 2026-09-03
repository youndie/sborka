plugins {
    // `embedded-kotlin`, not `kotlin-dsl`: this module holds no plugin, and `kotlin-dsl` would have it
    // announce on every build that it found no plugin descriptors. Kotlin at Gradle's own embedded
    // version, which is what the two plugin modules are compiled against anyway.
    `embedded-kotlin`
    // SBORKA IS LINTED BY THE FORMATTER SBORKA SHIPS, at the version `:core` generates from the same
    // catalog entry `sborka.lint` hands to consumers. A repository that ships a linter and does not
    // run it is the shape of defect its own checks exist to catch.
    alias(libs.plugins.ktlint)
}

ktlint {
    version.set(libs.versions.ktlintTool)
    filter { exclude { it.file.path.contains("/build/generated/") } }
}

// What both halves of sborka need and neither owns: the reference `.editorconfig` and the version of
// the release this jar came from. A module of its own rather than a copy in each, because the thing
// being shared is a FILE — and a style file that exists twice is two style files that agree today.

// The reference `.editorconfig` and the release's version are data; `Joins` and `ClassFile` are the
// one piece of logic here, and they are here because BOTH halves of sborka would otherwise need it —
// the settings plugin registers `kapkanJoins`, and this module is what the settings plugin is allowed
// to depend on.

dependencies {
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

// THE RELEASE'S OWN VERSION, generated into the jar.
//
// `sborka.settings` wires in the published version catalog, and the catalog is released together with
// the conventions — so the coordinate it asks for has to be this build's version and not a number
// typed beside it.
val generateVersionConstant =
    tasks.register("generateVersionConstant") {
        val outputDir = layout.buildDirectory.dir("generated/sborka/kotlin")
        val currentVersion = version.toString()
        val ktlint = libs.versions.ktlintTool.get()
        inputs.property("version", currentVersion)
        inputs.property("ktlint", ktlint)
        outputs.dir(outputDir)
        doLast {
            val target = outputDir.get().asFile.resolve("ru/workinprogress/sborka/internal/SborkaVersion.kt")
            target.parentFile.mkdirs()
            target.writeText(
                """
                package ru.workinprogress.sborka.internal

                /** Generated: the numbers this release of sborka was built with. */
                public object SborkaVersion {
                    /** The version of the sborka build that produced this jar. */
                    public const val CURRENT: String = "$currentVersion"

                    /**
                     * The ktlint `sborka.lint` runs by default.
                     *
                     * Generated rather than written into the convention, so that the formatter sborka
                     * is linted by and the formatter it hands to nineteen repositories are the same
                     * number in the same file.
                     */
                    public const val DEFAULT_KTLINT: String = "$ktlint"
                }

                """.trimIndent(),
            )
        }
    }

sourceSets.main {
    kotlin.srcDir(generateVersionConstant)
}
