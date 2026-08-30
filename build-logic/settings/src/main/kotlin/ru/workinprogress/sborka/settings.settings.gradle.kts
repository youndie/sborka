package ru.workinprogress.sborka

import ru.workinprogress.sborka.internal.EditorconfigReference
import ru.workinprogress.sborka.internal.SborkaVersion

// The settings-level half of sborka: where dependencies are looked for, which shared versions are in
// scope, and the one check that is about the repository rather than about a module.
//
// Applied in `settings.gradle.kts`:
//
//     plugins { id("ru.workinprogress.sborka.settings") version "…" }

// WHERE DEPENDENCIES ARE LOOKED FOR, WITH THE FILTERS ON.
//
// The filters are the point. A repository declared without a content filter is asked for EVERY
// coordinate the build resolves, which costs a round trip per miss and — the part that actually
// bites — lets an unrelated group be answered by the wrong server. One repository in the portfolio
// has the snapshot repository wired in unfiltered on both sides while two of its neighbours have
// already fixed exactly that.
//
// This plugin can only configure the RESOLUTION side. `pluginManagement` is evaluated before any
// settings plugin is applied — including this one, which is itself fetched through it — so the
// snapshot repository still has to be spelled out there by hand. `docs/migration.md` carries the
// lines.
@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    // A module that declares its own repositories declares them for itself only, and the build then
    // resolves the same coordinate from different places depending on which module asked. Refusing
    // that outright is right for most repositories here — and impossible for one kind.
    //
    // A module with a `js` or `wasmJs` target does not declare a repository; the KOTLIN PLUGIN does,
    // an ivy repository for the Node distribution, and it adds it to the project. FAIL_ON_PROJECT_REPOS
    // then refuses a build over a repository nobody in it wrote, with "added by unknown code" — and
    // declaring the same ivy repository in settings does not help, because the mode objects to the
    // project-level declaration existing at all.
    //
    // So `sborka.repositoriesMode=PREFER_SETTINGS` is the way to say it. The guarantee is nearly the
    // same — settings repositories still win and project ones are ignored — the difference being that
    // it stops shouting. Two repositories in the portfolio have JS targets; the other seventeen keep
    // the refusal.
    val requested = providers.gradleProperty("sborka.repositoriesMode").getOrElse("FAIL_ON_PROJECT_REPOS")
    val known = RepositoriesMode.entries.associateBy { it.name }
    repositoriesMode.set(
        known[requested]
            ?: error("sborka.repositoriesMode=$requested is not one of ${known.keys.sorted()}"),
    )

    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            mavenContent {
                includeGroupByRegex("ru\\.workinprogress.*")
                includeGroupByRegex("io\\.github\\.youndie.*")
                includeGroupByRegex("io\\.konekt.*")
            }
        }
        // A repository named from the outside, for the one case a settings plugin cannot serve on its
        // own: a build that has to resolve the catalog from somewhere other than the snapshot server —
        // sborka's own CI, checking a catalog it has just published to a directory, and a repository
        // bisecting an old version out of a local mirror.
        providers.gradleProperty("sborka.catalogRepository").orNull?.let { url ->
            maven(url) {
                name = "sborka-catalog-override"
                mavenContent { includeGroupByRegex("ru\\.workinprogress\\.sborka.*") }
            }
        }
    }

    versionCatalogs {
        // THE PORTFOLIO'S VERSIONS, as a catalog a build reads rather than a file it copies.
        //
        // Named `wip` and not `libs`: `libs` stays the repository's own, and a repository has versions
        // that are nobody else's business. What is here is only what more than one of them has to
        // agree on.
        //
        // The version is the plugin's own, generated into the jar at build time. A second number typed
        // beside it is a number that drifts, and a build resolving one release's conventions against
        // another release's versions is a combination nobody tested.
        //
        // `sborka.catalog=off` skips it. Two callers need that and both are real: a repository that
        // wants the conventions and not the versions, and the bootstrap — the very first build of
        // sborka itself, when no catalog has been published for the conventions to ask for. A settings
        // plugin resolves its catalog before any task in the same invocation has run, so a build
        // cannot publish the thing it is about to read.
        val catalogWanted = providers.gradleProperty("sborka.catalog").orNull != "off"
        if (catalogWanted) {
            val catalogVersion = providers.gradleProperty("sborka.catalogVersion").getOrElse(SborkaVersion.CURRENT)
            create("wip") {
                from("ru.workinprogress.sborka:catalog:$catalogVersion")
            }
        }
    }
}

// THE STYLE FILE ITSELF, checked rather than assumed.
//
// `sborka.lint` pins which ktlint runs. That settles half the question: ktlint reads `.editorconfig`,
// so the same version of the tool with a different `.editorconfig` is a different formatter. The
// portfolio has eleven distinct ones and four repositories with none at all.
//
// It lives in the SETTINGS plugin, not in `sborka.lint`, and that is the whole reason this file
// exists. `.editorconfig` is one file per repository, so the check is one per repository — and a
// check that only runs when somebody remembers to apply a module-level plugin to the ROOT project is
// a check that silently does not run, which is the shape of failure it was written to catch.
gradle.rootProject {
    val editorconfig = layout.projectDirectory.file(".editorconfig")

    val updateEditorconfig =
        tasks.register("updateEditorconfig") {
            group = "verification"
            description = "Writes sborka's reference .editorconfig to the root of this repository"
            outputs.file(editorconfig)
            val target = editorconfig.asFile
            doLast {
                val reference = EditorconfigReference.text()
                if (!target.isFile) {
                    target.writeText(reference)
                    return@doLast
                }
                val existing = target.readText()
                // A REPOSITORY MAY ADD TO THE SHARED FILE, and one already does: s3kn keeps a
                // `[docs/spec/**]` section because its vendored specification is compared byte for
                // byte by the tests, and a stripped trailing space breaks a test rather than a style.
                // So the tail is preserved and only the shared head is rewritten.
                //
                // A file whose head is NOT the reference is not something this task can classify —
                // every line of it might be deliberate — so it refuses rather than guesses.
                check(existing.startsWith(reference) || existing.isBlank()) {
                    "${target.path} does not start with sborka's reference, so this task cannot tell " +
                        "which of its lines are the shared ones and which are this repository's. " +
                        "Merge by hand: put the reference at the top and keep whatever this repository " +
                        "adds below it."
                }
                target.writeText(reference + existing.removePrefix(reference))
            }
        }

    val checkEditorconfig =
        tasks.register("checkEditorconfig") {
            group = "verification"
            description = "Checks that .editorconfig is the one sborka ships"
            val target = editorconfig.asFile
            val optedOut = providers.gradleProperty("sborka.editorconfig").orNull == "custom"
            val updateTaskName = updateEditorconfig.name
            // Cheap enough to run every time, and a stale "up to date" on a style check is worse than
            // the second it costs: the file it reads is edited by hand, outside any task's outputs.
            outputs.upToDateWhen { false }
            doLast {
                if (optedOut) {
                    logger.lifecycle("checkEditorconfig: sborka.editorconfig=custom — not checked")
                    return@doLast
                }
                check(target.isFile) {
                    "no .editorconfig at the root of this repository. ktlint reads it, so without one " +
                        "the pinned tool version settles only half of what the style is. Run " +
                        "`./gradlew $updateTaskName`, or set sborka.editorconfig=custom in " +
                        "gradle.properties if this repository means to differ."
                }
                // STARTS WITH, not equals. The shared rules have to be there and have to be
                // unedited; what a repository appends after them is its own business, and one
                // repository has a real reason to append — s3kn's vendored specification is compared
                // byte for byte by its tests, so `[docs/spec/**]` turns the whitespace rules off for
                // that tree. A check demanding equality would have forced that repository to opt out
                // of the shared style entirely to keep one section it needs.
                check(target.readText().startsWith(EditorconfigReference.text())) {
                    ".editorconfig does not start with the one sborka ships, so this repository is " +
                        "formatted by a different formatter than the rest. Run `./gradlew " +
                        "$updateTaskName` to put the shared rules back at the top — anything this " +
                        "repository adds below them is kept — or set sborka.editorconfig=custom in " +
                        "gradle.properties to differ on purpose."
                }
            }
        }

    // EVERY `check` IN THE BUILD, not only the root's. A root project that holds no code has no
    // `check` task of its own, and hanging the guard off a task that may not exist is how a guard
    // ends up never running — which is the same silence it is guarding against.
    allprojects {
        tasks.matching { it.name == "check" }.configureEach { dependsOn(checkEditorconfig) }
    }
}
