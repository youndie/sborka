package ru.workinprogress.sborka

import org.jlleitschuh.gradle.ktlint.KtlintExtension
import ru.workinprogress.sborka.internal.SborkaVersion

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
// `ru.workinprogress.sborka.settings`: one file per repository is one check per repository, and a
// check hanging off a module-level plugin only runs where somebody applied it.

plugins {
    id("org.jlleitschuh.gradle.ktlint")
}

configure<KtlintExtension> {
    version.set(providers.gradleProperty("sborka.ktlintVersion").orElse(SborkaVersion.DEFAULT_KTLINT))

    // Generated sources are not ours to format, and a formatter that rewrites them makes the
    // generator's next run look like a change.
    filter {
        exclude { it.file.path.contains("/build/generated/") }
    }
}
