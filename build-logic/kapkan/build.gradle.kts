plugins {
    // `embedded-kotlin`, the same choice `:core` makes and for a sharper reason here: this module
    // holds no plugin, and asking for `org.jetbrains.kotlin.jvm` by version would put a SECOND Kotlin
    // Gradle plugin on this build's script classpath beside the one `kotlin-dsl` already loaded.
    `embedded-kotlin`
    // SBORKA IS LINTED BY THE FORMATTER SBORKA SHIPS. The rules in this module are linted by the tool
    // that will load them.
    alias(libs.plugins.ktlint)
}

ktlint {
    version.set(libs.versions.ktlintTool)
    filter { exclude { it.file.path.contains("/build/generated/") } }
}

// kapkan — the rules, as a ktlint rule set.
//
// Each rule encodes ONE class of defect that this stack paid a stand run to find, and carries the
// item it was found in. What is deliberately absent is configuration: a rule is in the set or it is
// not, and the only knob is a suppression that has to say why (`suppression-needs-a-reason`).
//
// The mechanism is ktlint rather than a compiler plugin because none of these rules needs a type.
// The one that did — "a @Test that returns a value" — is not here: it is `DeclaredTests` in
// `sborka.test`, which compares what was declared with what JUnit ran and therefore catches every
// reason a test did not execute rather than one syntactic shape of it. See `docs/kapkan.md` §2.1.

dependencies {
    // COMPILE ONLY, BOTH OF THEM. ktlint's worker already has the rule API on its classpath; a rule
    // set jar that brings its own copy brings a second `RuleId` class, and the engine matches rule
    // ids by the class it loaded rather than by the name.
    compileOnly(libs.ktlint.rule.engine.core)
    compileOnly(libs.ktlint.cli.ruleset.core)

    testImplementation(libs.ktlint.rule.engine.core)
    testImplementation(libs.ktlint.cli.ruleset.core)
    // A rule is tested by running the engine over a snippet: the same entry point ktlint-gradle uses,
    // so a rule that works in the test works in a build.
    testImplementation(libs.ktlint.rule.engine)
    // Beside kapkan in one test, to pin how ktlint's own `ktlint-suppression` rule reads a
    // `@Suppress("ktlint:kapkan:…", "a reason")` — the whole suppression design rests on it leaving
    // the second string alone.
    testImplementation(libs.ktlint.ruleset.standard)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // ktlint's engine logs through slf4j and takes no binding with it. Without one every call into
    // the engine dies in a static initialiser naming `org/slf4j/LoggerFactory` — a message about a
    // class, for a missing artefact. Silent on purpose: what the tests read is the lint errors.
    testRuntimeOnly(libs.slf4j.nop)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
