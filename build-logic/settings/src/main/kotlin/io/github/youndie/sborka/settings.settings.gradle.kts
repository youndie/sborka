package io.github.youndie.sborka

import io.github.youndie.sborka.internal.EditorconfigReference
import io.github.youndie.sborka.internal.Joins
import io.github.youndie.sborka.internal.MethodSizes
import io.github.youndie.sborka.internal.SborkaVersion
import io.github.youndie.sborka.internal.Suppressions

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

    // THE SAME OUTPUT, PER MODULE, because a finding has to be able to say which module it is in
    // before anything can be scoped to a module. `allprojects` is complete here: settings created
    // every project object, and this block only needs their paths and their build directories.
    val classDirByProject = allprojects.associate { it.path to it.layout.buildDirectory.dir("classes") }

    // WHICH MODULES ARE HOT — declared by the repository, because nothing static knows.
    //
    // Reachability from a route is computable with the reader that already reads calls, and it is
    // still not hotness: konekt's own OpenAPI document builder is reachable from a route and runs
    // once per process. A person naming two modules is one line of configuration and is right for
    // the reason that matters — they know which process runs under load.
    //
    // ABSENT MEANS NOTHING IS HOT, and that is the property that lets a gate ship at all: taking a
    // new version of sborka cannot fail a build that has not asked for it.
    val hotModules =
        providers
            .gradleProperty("sborka.perflint.hot")
            .orNull
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.startsWith(":")) it else ":$it" }
            .toSet()
    val sourceDirs = files(allprojects.map { it.layout.projectDirectory.dir("src") })
    val reportFile = layout.buildDirectory.file("reports/kapkan/joins.txt")
    val methodSizesReport = layout.buildDirectory.file("reports/kapkan/method-sizes.txt")
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

    // `kapkanMethodSizes` — WHAT THE JIT IS BEING ASKED TO INLINE.
    //
    // The same shape as `kapkanJoins` above and for the same reasons: one task per repository, in the
    // settings plugin so that it needs nobody to remember it, over compiled output because the source
    // cannot answer the question. A `suspend` function becomes a state machine and an `inline`
    // function is copied into its caller, so how long a body reads says little about how many bytes
    // C2 measures.
    //
    // NOT IN `check`, AND THE REASON IS NOT THAT THE NUMBERS MEAN NOTHING — that was measured and it
    // came back the other way. zavarnik's bench under load at 31 418 rps, with PrintCompilation and
    // PrintInlining: three of the ten methods over FreqInlineSize appear in the compiler's log as
    // "hot method too big" — `Pricing::quote` (1827 bytes), an `invokeSuspend` (816) and a
    // serializer's `deserialize` (374). The reason it still only prints is the step after that,
    // which nobody has taken: a refusal to inline is not a measured cost, and a build that failed on
    // one would be enforcing a proxy.
    val kapkanMethodSizes =
        tasks.register("kapkanMethodSizes") {
            group = "verification"
            description = "Lists method bodies larger than the thresholds C2 inlines by"
            outputs.upToDateWhen { false }

            doLast {
                // ONE SCAN PER MODULE rather than one over everything: the scan is the same files
                // either way, and this is what puts a module's name on a finding. Without that a
                // scope can be declared and cannot be applied.
                val byModule =
                    classDirByProject
                        .mapValues { (_, dir) -> MethodSizes.scan(listOf(dir.get().asFile)) }
                        .filterValues { it.classesRead > 0 }
                val report =
                    MethodSizes.Report(
                        classesRead = byModule.values.sumOf { it.classesRead },
                        methodsRead = byModule.values.sumOf { it.methodsRead },
                        findings = byModule.values.flatMap { it.findings }.sortedByDescending { it.bytes },
                        assertions =
                            byModule.values
                                .flatMap { it.assertions }
                                .sortedByDescending { it.assertions },
                        patternsCompiled = byModule.values.flatMap { it.patternsCompiled },
                        chains = byModule.values.flatMap { it.chains }.sortedByDescending { it.materialisations },
                        unwalked = byModule.values.flatMap { it.unwalked },
                    )

                // THE SAME GUARD AS THE JOINS REPORT, and it earns its place for the same reason: a
                // report over no class files is not an empty report, it is a report that read nothing.
                check(report.classesRead > 0) {
                    "kapkanMethodSizes read no class files under ${classDirs.files.size} build " +
                        "directories. It reads compiled output, so something has to have compiled: " +
                        "run it after `./gradlew classes` or `./gradlew build`."
                }

                // A SCOPE THAT MATCHES NOTHING SCOPES NOTHING, AND SAYS SO. Both halves are failures
                // rather than warnings: a module path with a typo in it and a module with no JVM
                // output both leave `sborka.perflint.hot` naming a set of findings that is empty,
                // and an empty gate is indistinguishable from a passing one.
                val unknown = hotModules - classDirByProject.keys
                check(unknown.isEmpty()) {
                    "sborka.perflint.hot names ${unknown.sorted().joinToString()}, which this build " +
                        "has no project for. The paths it does have: " +
                        classDirByProject.keys.sorted().joinToString()
                }
                val silent = hotModules - byModule.keys
                check(silent.isEmpty()) {
                    "sborka.perflint.hot names ${silent.sorted().joinToString()}, which compiled no " +
                        "class files this reader can see. Kotlin/Native and wasm produce klibs, not " +
                        "class files, so a module with only those targets cannot be scoped this way."
                }

                val moduleOf =
                    byModule
                        .flatMap { (module, moduleReport) ->
                            (
                                moduleReport.findings + moduleReport.patternsCompiled +
                                    moduleReport.chains + moduleReport.assertions
                            ).map { it.toString() to module }
                        }.toMap()

                // The module in front of every line, and `[hot]` on the ones a gate would judge.
                // Nothing fails yet — the failure arrives with the rule that has a message and a
                // suppression to offer (`B-03`), because a gate with neither is a gate people
                // switch off.
                fun label(method: MethodSizes.Method): String {
                    val module = moduleOf[method.toString()] ?: "?"
                    val hot = if (module in hotModules) " [hot]" else ""
                    return "$module$hot $method"
                }

                val sizeLines =
                    report.findings.map { method ->
                        val crossed =
                            method.crossed.joinToString(", ") { "${it.flag} (${it.bytes})" }
                        "${label(method)}: ${method.bytes} bytes — over $crossed"
                    }

                // A PATTERN BUILT ON EVERY CALL, which is the same class-file walk answering a
                // different question. `Regex(…)` is a constant with a compiler attached: in
                // `<clinit>` it is paid for once, in a method body it is paid for per invocation —
                // on the zavarnik stand this was the largest user-code source of allocation, 6.5% of
                // bytes. `<clinit>` is not counted, so what is listed is the rebuilt ones.
                val patternLines =
                    report.patternsCompiled.map { method ->
                        "${label(method)}: ${method.patternsCompiled} pattern(s) built per call — " +
                            "a Regex in <clinit> is built once"
                    }

                // A CHAIN THAT ALLOCATES A CONTAINER PER LINK, which is the same class-file walk
                // asking its third question. `filter`/`map` are inline, so the chain is not visible
                // as calls — what is visible is the container each link materialises, and a
                // sequence or a flow materialises once at the end. Two or more in one body is the
                // finding; the count is an upper bound, because a class file carries no dataflow
                // and two unrelated containers in one method look the same from here.
                //
                // The number behind the question: on konekt the methods that answer "two or more"
                // own 30–32 % of everything user code allocates, and rewriting the two largest took
                // them to zero — see docs/research/research-perf-lint.md §1.4 and §1.7.
                val chainLines =
                    report.chains.map { method ->
                        "${label(method)}: ${method.materialisations} eager materialisation(s) — " +
                            "a sequence materialises once, at the end"
                    }

                // THE NULL CHECKS, as a count rather than a list. Kotlin emits `Intrinsics.check…`
                // on parameters and on the results of Java calls; `-Xno-param-assertions` and
                // `-Xno-call-assertions` remove them. Naming every method that has one would name
                // most of the build, so the report says how many there are and which bodies carry
                // the most — enough to decide whether the flags would remove anything worth arguing
                // about, and not a list anybody would read.
                val assertionSites = report.assertions.sumOf { it.assertions }
                val assertionLines =
                    if (report.assertions.isEmpty()) {
                        emptyList()
                    } else {
                        listOf(
                            "assertions: $assertionSites Intrinsics.check* call site(s) in " +
                                "${report.assertions.size} method(s) — what -Xno-param-assertions " +
                                "and -Xno-call-assertions would remove",
                        ) +
                            report.assertions.take(10).map { method ->
                                "  ${method.className}.${method.name}${method.descriptor}: " +
                                    "${method.assertions}"
                            }
                    }

                // A BODY THE WALK REFUSED IS NAMED, NOT COUNTED AS ZERO. The two questions above are
                // answered by stepping through instructions; a walk that did not land exactly on the
                // end of a body has not answered them, and printing its zeroes would be the silence
                // this task exists to end.
                val unwalkedLines =
                    if (report.unwalked.isEmpty()) {
                        emptyList()
                    } else {
                        listOf(
                            "NOT WALKED — the call counts above do not include these " +
                                "${report.unwalked.size} method(s):",
                        ) + report.unwalked.take(10).map { "  $it" }
                    }

                val lines = sizeLines + patternLines + chainLines + assertionLines + unwalkedLines

                val summary =
                    "kapkanMethodSizes: ${report.classesRead} class file(s), " +
                        "${report.methodsRead} method(s) with a body, " +
                        "${report.chains.size} with ${MethodSizes.CHAIN_FROM} or more eager " +
                        "materialisations, ${report.patternsCompiled.size} building a pattern per " +
                        "call, ${report.findings.size} over ${MethodSizes.REPORT_FROM.flag} " +
                        "(${MethodSizes.REPORT_FROM.bytes} bytes), of which " +
                        "${report.findings.count { it.bytes > MethodSizes.HUGE_METHOD_LIMIT.bytes }} " +
                        "over ${MethodSizes.HUGE_METHOD_LIMIT.flag} — a method that long is not " +
                        "compiled at all"

                // THE GATE, and it judges ONE of the three questions.
                //
                // The pattern rule is the only one cheap enough: eight findings in 53 423 methods
                // across eleven repositories, against 325 for the chain question and 1058 for the
                // size one. A rule that fires a thousand times is a counter, and a build that fails
                // on a counter gets an exemption within a week. The share behind it is the largest
                // of the three: 6.45 % of every byte the zavarnik stand allocated on /business,
                // two thirds of everything user code allocated there.
                //
                // A finding is answered either by moving the pattern into `<clinit>` or by a
                // `@Suppress` beside the line that builds it — three of the eight findings in the
                // portfolio are constructors and one is interpolated, so the suppression is the
                // ordinary outcome rather than the exception.
                val gateable = report.patternsCompiled.filter { moduleOf[it.toString()] in hotModules }
                val unanswered =
                    gateable.mapNotNull { method ->
                        val sites = Suppressions.sitesOf(sourceDirs.files, method.className)
                        when {
                            // A FINDING WHOSE SOURCE CANNOT BE FOUND IS NOT A SUPPRESSED ONE. Reading
                            // "no file" as "answered" is how a gate goes quiet without anybody
                            // deciding that it should.
                            sites == null -> {
                                "${label(method)}: ${method.patternsCompiled} pattern(s) built per " +
                                    "call, and no source file could be attributed to " +
                                    "${method.className} to look for a suppression"
                            }

                            sites.suppressed >= method.patternsCompiled -> {
                                null
                            }

                            else -> {
                                "${label(method)}: ${method.patternsCompiled - sites.suppressed} " +
                                    "pattern(s) built per call and not answered for"
                            }
                        }
                    }

                check(unanswered.isEmpty()) {
                    unanswered.joinToString(
                        prefix =
                            "kapkanMethodSizes: a pattern is compiled on every call inside a module " +
                                "sborka.perflint.hot names.\n\nOn the zavarnik stand one Regex built " +
                                "inside a handler was 6.45 % of every byte the service allocated — " +
                                "two thirds of everything user code allocated there " +
                                "(bench/profile/results/baseline/business.alloc.collapsed; " +
                                "sborka docs/research/research-perf-lint.md §2.1).\n\n",
                        separator = "\n",
                        postfix =
                            "\n\nMove the pattern into a `<clinit>` — a top-level or companion `val` — " +
                                "where it is compiled once. Where it cannot be moved (a pattern " +
                                "interpolated from an argument, a constructor whose instance lives " +
                                "as long as the process), answer for it beside the line that builds " +
                                "it:\n\n" +
                                "    @Suppress(\"${Suppressions.PATTERN_RULE}\", \"why this one is " +
                                "rebuilt\")\n\n" +
                                "The reason is not optional: kapkan's suppression-needs-a-reason " +
                                "fails a suppression without one.",
                    )
                }

                // WHAT THE SCOPE COVERS, printed whether or not anything is scoped. A line saying
                // "nothing declared hot" is what tells a reader that the silence is a decision and
                // not a rule that failed to run.
                val scope =
                    if (hotModules.isEmpty()) {
                        "kapkanMethodSizes: nothing declared hot — set sborka.perflint.hot to the " +
                            "modules whose findings should be judged (`:server`, `:shared:domain`); " +
                            "every finding above is a report until something is"
                    } else {
                        val hotFindings =
                            (report.patternsCompiled + report.chains).count {
                                moduleOf[it.toString()] in hotModules
                            }
                        "kapkanMethodSizes: hot modules ${hotModules.sorted().joinToString()} — " +
                            "$hotFindings finding(s) in them, marked [hot] above"
                    }

                val target = methodSizesReport.get().asFile
                target.parentFile.mkdirs()
                target.writeText((lines + summary + scope).joinToString("\n", postfix = "\n"))

                lines.forEach { logger.lifecycle(it) }
                logger.lifecycle(summary)
                logger.lifecycle(scope)
                logger.lifecycle(
                    "kapkanMethodSizes: written to ${target.relativeTo(root).invariantSeparatorsPath}",
                )
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
        val classesTasks =
            allprojects.flatMap { project ->
                project.tasks.names
                    .filter { it.endsWith("Classes") && !it.startsWith("metadata") }
                    .map { "${project.path}:$it" }
            }
        kapkanJoins.configure { dependsOn(classesTasks) }
        kapkanMethodSizes.configure { dependsOn(classesTasks) }

        // IN `check` ONLY WHERE A REPOSITORY DECLARED A SCOPE, which is the whole opt-in.
        //
        // The task prints three reports and fails on one of them — a pattern compiled per call
        // inside a hot module. Where nothing is declared hot it cannot fail, so putting it in
        // `check` would buy a slower build and no gate; where something is, the gate has to run
        // somewhere a person looks, and `check` is that place. This is also why the size report
        // staying out of `check` is unchanged: it is the failure that is scoped, not the printing.
        if (hotModules.isNotEmpty()) {
            allprojects {
                tasks.matching { it.name == "check" }.configureEach { dependsOn(kapkanMethodSizes) }
            }
        }
    }
}
