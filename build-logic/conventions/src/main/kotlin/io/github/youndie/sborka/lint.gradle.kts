package io.github.youndie.sborka

import io.github.youndie.sborka.internal.SborkaVersion
import org.jlleitschuh.gradle.ktlint.KtlintExtension

// One formatter, one version of it, one configuration file.
//
// The portfolio arrived at four different wirings of the same tool — the plugin applied from a root
// `subprojects` block, the plugin declared and never applied, ktlint driven as a CLI through a
// hand-written `JavaExec` pair, and nothing at all — and at eleven distinct `.editorconfig` files
// with four repositories missing one. The catalog key `ktlint` meanwhile named two different things:
// the TOOL version in nine repositories and the PLUGIN version in four.
//
// The tool version is pinned from here rather than left to the plugin's default. Left to the default
// the style shifts whenever the plugin is bumped, which is precisely the change nobody reads the
// diff of.
//
// The `.editorconfig` the tool READS is the other half of the same question, and it is checked by
// `io.github.youndie.sborka.settings`: one file per repository is one check per repository, and a
// check hanging off a module-level plugin only runs where somebody applied it.

plugins {
    id("org.jlleitschuh.gradle.ktlint")
}

// THE RULES TRAVEL WITH THE FORMATTER, at the version of the sborka release that carried them.
//
// `ktlintRuleset` is ktlint-gradle's own configuration for rule set jars: what lands on it is loaded
// by ktlint's worker through `META-INF/services`, beside the standard rules and on the same
// classpath. Named by coordinate rather than depended on, because these conventions must not carry
// kapkan's classes: they would then be on the Gradle plugin classloader, which is not where ktlint
// looks.
//
// A repository that gets the plugin gets the rules — there is no opt-in, and no property to turn
// them off. What can be turned off is one rule in one place, by a `@Suppress` that has to say why;
// see `kapkan:suppression-needs-a-reason`.
//
// AND THE VERSION IS THIS RELEASE'S. `sborka.lint` and the rule set are published together, so a jar
// asking for a number typed beside it would be asking for whatever was current when somebody typed.
dependencies {
    add("ktlintRuleset", "io.github.youndie.sborka:kapkan:${SborkaVersion.CURRENT}")
}

configure<KtlintExtension> {
    version.set(providers.gradleProperty("sborka.ktlintVersion").orElse(SborkaVersion.DEFAULT_KTLINT))

    // Generated sources are not ours to format, and a formatter that rewrites them makes the
    // generator's next run look like a change.
    filter {
        exclude { it.file.path.contains("/build/generated/") }
    }
}
