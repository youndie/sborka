package io.github.youndie.sborka

import io.github.youndie.sborka.internal.EditorconfigReference
import io.github.youndie.sborka.internal.Joins
import io.github.youndie.sborka.internal.SborkaVersion

// The settings-level half of sborka: where dependencies are looked for, which shared versions are in
// scope, and the one check that is about the repository rather than about a module.
//
// Applied in `settings.gradle.kts`:
//
//     plugins { id("io.github.youndie.sborka.settings") version "…" }

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

    // EVERY FILTERED REPOSITORY BEFORE THE UNFILTERED ONE, and the order is the whole of it.
    //
    // Gradle asks repositories in the order they are declared and stops at the first that answers. A
    // filter does not make a repository cheaper to ask — it makes it SKIPPED for coordinates it does
    // not claim — so a filtered repository costs nothing when it is first and everything when it is
    // last: with `mavenCentral()` in front of the snapshot server, every first-party coordinate in
    // the portfolio pays a round trip to Central that is required to miss.
    //
    // It does not always miss politely. sborka's own release check failed on
    // `ru.workinprogress.sborka:catalog` — the coordinate of the time — with "Received status code
    // 429 from server: Too Many Requests": a rate limit, on a group Central has never held, for a
    // question that should never have been asked. `mavenCentral()` last is not a preference; it is
    // the only position at which an unfiltered repository is asked exactly the coordinates the
    // others declined.
    //
    // AND THE OVERRIDE BEFORE WHAT IT OVERRIDES. It was last, which made it a fallback wearing the
    // word "override": a version present on the snapshot server was answered from there and the
    // directory never consulted.
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        // A repository named from the outside, for the one case a settings plugin cannot serve on its
        // own: a build that has to resolve the catalog from somewhere other than the snapshot server —
        // sborka's own CI, checking a catalog it has just published to a directory, and a repository
        // bisecting an old version out of a local mirror.
        providers.gradleProperty("sborka.catalogRepository").orNull?.let { url ->
            maven(url) {
                name = "sborka-catalog-override"
                mavenContent { includeGroupByRegex("io\\.github\\.youndie\\.sborka.*") }
            }
        }
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            mavenContent {
                includeGroupByRegex("ru\\.workinprogress.*")
                includeGroupByRegex("io\\.github\\.youndie.*")
                includeGroupByRegex("io\\.konekt.*")
            }
        }
        mavenCentral()
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
                from("io.github.youndie.sborka:catalog:$catalogVersion")
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
                val existing = EditorconfigReference.normalise(target.readText())
                // A REPOSITORY MAY ADD TO THE SHARED FILE, and one already does: it keeps a
                // `[docs/spec/**]` section because a vendored specification is compared byte for byte
                // by its tests, and a stripped trailing space breaks a test rather than a style. So
                // the tail is preserved and only the shared head is rewritten.
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
                // repository has a real reason to append — a vendored specification compared byte
                // for byte by its tests, so `[docs/spec/**]` turns the whitespace rules off for that
                // tree. A check demanding equality would have forced that repository to opt out of
                // the shared style entirely to keep one section it needs.
                // NORMALISED, because a checkout decides this and not the repository. Git on Windows
                // rewrites text files to CRLF unless told otherwise, so `.editorconfig` arrives on a
                // windows-latest runner byte-different from the one this jar ships — and the message
                // below then says the repository is formatted by a different formatter, about a file
                // nobody edited. Found on the one build here whose matrix includes Windows.
                check(EditorconfigReference.normalise(target.readText()).startsWith(EditorconfigReference.text())) {
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

// `kapkanJoins` — WHAT THIS REPOSITORY BUILT AND NEVER CALLED.
//
// The rest of kapkan is ktlint rules, and they see one file at a time. This one cannot: "nothing
// mentions this" is a question about the whole build, and shashki's own measurement says so — two
// protocol types looked unused until `:server` was compiled beside them.
//
// IT LIVES IN THE SETTINGS PLUGIN FOR THE REASON `checkEditorconfig` DOES, plus one of its own. The
// reason it shares: this is one task per repository, and a task hung off a module-level plugin runs
// only where somebody remembered to apply that plugin to the root. The reason of its own: applying a
// PROJECT convention to the root project puts the conventions jar on every module's classpath, and a
// module then asking for a plugin BY VERSION fails with "already on the classpath with an unknown
// version" — a message about neither the plugin nor the module. The settings jar has no such effect.
//
// NOT IN `check`, AND THAT IS A MEASUREMENT RATHER THAN CAUTION. On shashki's compiled output the
// rule names 46 of 437 classes and not one of them is a defect: an `inline` function leaves no
// reference, a `const val` is folded into its call site, and Kotlin's own idiom is to declare a
// helper beside its only user. A rule with that ratio in `check` is a rule somebody switches off,
// and it takes the cases it was right about with it. So it writes a list, and the list is read.
gradle.rootProject {
    // JVM OUTPUT ONLY, and that is what there is. Kotlin/Native and wasm produce klibs, whose
    // references live inside a serialised IR with no supported reader; every application in this
    // portfolio keeps a JVM or desktop target anyway, because that is the only target a screenshot
    // can be taken on. Written down as a condition of use rather than left as a surprise.
    val classDirs = files(allprojects.map { it.layout.buildDirectory.dir("classes") })
    val sourceDirs = files(allprojects.map { it.layout.projectDirectory.dir("src") })
    val reportFile = layout.buildDirectory.file("reports/kapkan/joins.txt")
    val root = layout.projectDirectory.asFile

    val kapkanJoins =
        tasks.register("kapkanJoins") {
            group = "verification"
            description = "Lists declarations nothing outside their own file mentions"
            // The inputs are read from disk by another task's output directory, and the answer changes
            // whenever any module recompiles. Cheaper to redo than to be wrong about.
            outputs.upToDateWhen { false }

            doLast {
                val report = Joins.scan(classDirs.files, sourceDirs.files)

                // A REPORT OVER NO CLASSES IS NOT AN EMPTY REPORT, it is a report that read nothing —
                // and printing "found 0" for it would be the exact silence kapkan exists to end.
                check(report.classesRead > 0) {
                    "kapkanJoins read no class files under ${classDirs.files.size} build directories. " +
                        "It reads compiled output, so something has to have compiled: run it after " +
                        "`./gradlew classes` or `./gradlew build`."
                }

                fun report(
                    finding: Joins.Finding,
                    verb: String,
                ): String {
                    val where = finding.file.relativeTo(root).invariantSeparatorsPath
                    val verdict =
                        if (finding.testsOnly) {
                            "only tests $verb it — built at one end and joined at neither"
                        } else {
                            "nothing in this repository ${verb}s it"
                        }
                    return "$where:${finding.line}:1: kapkan[${Joins.RULE.substringAfter(':')}]: " +
                        "${finding.qualifiedName.substringAfterLast('.')} is public and $verdict"
                }

                val lines =
                    report.findings.map { report(it, "mention") } +
                        report.functionFindings.map { report(it, "call") }

                val summary =
                    "kapkanJoins: ${report.classesRead} class file(s), " +
                        "${report.declarationsConsidered} declaration(s) and " +
                        "${report.functionsConsidered} function(s) considered, " +
                        "${report.findings.size + report.functionFindings.size} finding(s) of which " +
                        "${(report.findings + report.functionFindings).count {
                            it.testsOnly
                        }} reached only by tests, " +
                        "${report.suppressed.size} suppressed"

                val target = reportFile.get().asFile
                target.parentFile.mkdirs()
                target.writeText((lines + summary).joinToString("\n", postfix = "\n"))

                lines.forEach { logger.lifecycle(it) }
                logger.lifecycle(summary)
                logger.lifecycle("kapkanJoins: written to ${target.relativeTo(root).invariantSeparatorsPath}")
            }
        }

    // AFTER THE COMPILATIONS THAT PRODUCE CLASS FILES, and after those only. KGP registers a
    // `<target>MainClasses` task for JVM-like targets and for nothing else — `linuxX64` has none —
    // so this list is exactly the JVM output without naming a single target. `metadata…Classes` is
    // dropped because it assembles klibs, which this cannot read anyway.
    //
    // BY NAME, AND NOT BY `tasks.matching`. A predicate over a task collection has to CREATE every
    // task in every project to ask it its name, which is the configuration-avoidance mistake with a
    // measurable price — and it surfaced one of KGP's own deprecations on the way. `tasks.names` is
    // the registered names without instantiating anything, and `projectsEvaluated` is when every
    // project has registered its own.
    gradle.projectsEvaluated {
        kapkanJoins.configure {
            dependsOn(
                allprojects.flatMap { project ->
                    project.tasks.names
                        .filter { it.endsWith("Classes") && !it.startsWith("metadata") }
                        .map { "${project.path}:$it" }
                },
            )
        }
    }
}
