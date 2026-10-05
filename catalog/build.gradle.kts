plugins {
    base
    `version-catalog`
    `maven-publish`
    alias(libs.plugins.ktlint)
}

ktlint {
    version.set(libs.versions.ktlintTool)
}

val kapkanVersion: String =
    providers
        .gradleProperty("VERSION")
        .orElse(providers.gradleProperty("sborka.version"))
        .get()

// LINTED BY THE RULES THIS REPOSITORY PUBLISHES, not only by its formatter. The coordinate resolves
// to `build-logic`'s `:kapkan` through `includeBuild`, which is the same substitution a consumer gets
// from the repository — so a rule set that fails to build fails here rather than in somebody's
// migration.
dependencies {
    ktlintRuleset("io.github.youndie.sborka:kapkan:$kapkanVersion")
}

group = "io.github.youndie.sborka"
version = providers.gradleProperty("VERSION").orElse(providers.gradleProperty("sborka.version")).get()

catalog {
    versionCatalog {
        from(files("sborka.versions.toml"))
    }
}

publishing {
    publications.create<MavenPublication>("catalog") {
        artifactId = "catalog"
        from(components["versionCatalog"])
    }

    repositories {
        // A directory, so CI can publish the catalog and then resolve it from a second invocation of
        // the stand. That second run is the only place the coordinate in `sborka.settings` is checked
        // against a catalog that actually exists.
        maven {
            name = "local"
            url = uri(rootProject.layout.buildDirectory.dir("local-repo"))
        }
        maven {
            name = "wip"
            url = uri("https://reposilite.kotlin.website/snapshots")
            credentials {
                username = providers.gradleProperty("REPOSILITE_USER").orNull
                password = providers.gradleProperty("REPOSILITE_SECRET").orNull
            }
        }
    }
}

// THE GRADLE VERSION IS DECIDED IN ONE PLACE AND CHECKED WHERE IT IS REPEATED.
//
// `gradle` in sborka.versions.toml is that place. Two other files have to agree with it and nothing
// would notice if they stopped: the Renovate preset, whose Gradle rule decides which Gradle every
// repository is offered, and this repository's own wrapper. A preset still holding the old line would
// quietly keep the portfolio there while the catalog claimed otherwise.
//
// The rule is found by its SHAPE — datasources `gradle-version` and `docker`, name `gradle` — because
// the shape is half of what it holds. Matched by the `gradle-wrapper` manager, it held the wrapper and
// let the `gradle:<version>-jdk25-noble` image in every Dockerfile through to the next line; a rule
// that goes back to one manager is not found here, and the check says why.
val verifyGradleVersion =
    tasks.register("verifyGradleVersion") {
        val root = rootProject.layout.projectDirectory
        val catalogFile = layout.projectDirectory.file("sborka.versions.toml").asFile
        val presetFile = root.file("default.json").asFile
        val wrapperFile = root.file("gradle/wrapper/gradle-wrapper.properties").asFile
        inputs.files(catalogFile, presetFile, wrapperFile)
        doLast {
            val declared =
                Regex("""(?m)^gradle\s*=\s*"([^"]+)"""").find(catalogFile.readText())?.groupValues?.get(1)
                    ?: error("${catalogFile.name} declares no `gradle` version")

            val preset = groovy.json.JsonSlurper().parse(presetFile) as Map<*, *>
            val datasources = listOf("gradle-version", "docker")
            val gradleRule =
                (preset["packageRules"] as List<*>)
                    .filterIsInstance<Map<*, *>>()
                    .lastOrNull { rule ->
                        (rule["matchDatasources"] as? List<*>)?.containsAll(datasources) == true &&
                            (rule["matchDepNames"] as? List<*>)?.contains("gradle") == true &&
                            rule["allowedVersions"] != null
                    }
                    ?: error(
                        "${presetFile.name} has no allowedVersions rule for depName gradle on datasources " +
                            "$datasources: a rule that holds only the wrapper lets the build image through",
                    )
            val allowed = (gradleRule["allowedVersions"] as String).removePrefix("/").removeSuffix("/")
            check(Regex(allowed).containsMatchIn(declared)) {
                "${presetFile.name} allows Gradle /$allowed/ for the wrapper and the image, " +
                    "which does not admit $declared from ${catalogFile.name}"
            }

            check("gradle-$declared-" in wrapperFile.readText()) {
                "sborka's own wrapper does not name Gradle $declared, the version ${catalogFile.name} declares"
            }
        }
    }

// A RUNNER LABEL IS NOT HARNESS, AND THE HARNESS PRESET HAS TO KEEP SAYING SO.
//
// `automerge-harness.json` merges whatever the `github-actions` manager finds, and that manager reads
// the `runs-on:` labels too (datasource `github-runners`). A label decides the OS and the system
// packages a workflow builds against, and the workflow it matters to most — publish — is one no pull
// request runs: mongkn#17 moved its publish runner to a libmongoc floor no consumer had, green (#126).
//
// Renovate applies package rules in order and the last match wins, so this replays that order for a
// runner label: the last rule that could match one and says anything about `automerge` has to say
// `false`. A rule could match when every matcher it has admits the label — no `matchDatasources` or
// one naming `github-runners`, no `matchManagers` or one naming `github-actions`. And the held label
// has to sit in a group of its own: Renovate merges a grouped pull request only when every update in
// it may merge, so a label left in `ci actions` would hold every action major beside it.
val verifyHarnessPreset =
    tasks.register("verifyHarnessPreset") {
        val root = rootProject.layout.projectDirectory
        val presetFile = root.file("automerge-harness.json").asFile
        inputs.file(presetFile)
        doLast {
            val preset = groovy.json.JsonSlurper().parse(presetFile) as Map<*, *>
            val rules = preset["packageRules"] as List<*>

            fun Map<*, *>.admits(
                key: String,
                value: String,
            ): Boolean = (this[key] as? List<*>)?.contains(value) ?: true

            val runnerRules =
                rules.filterIsInstance<Map<*, *>>().filter { rule ->
                    rule.admits("matchDatasources", "github-runners") && rule.admits("matchManagers", "github-actions")
                }
            check(runnerRules.any { it["automerge"] == true }) {
                "${presetFile.name} no longer automerges the `github-actions` manager at all, " +
                    "so this check has nothing to hold the runner label against — re-read it"
            }
            val decisive =
                runnerRules.lastOrNull { "automerge" in it }
                    ?: error("${presetFile.name}: no rule decides automerge for a `github-runners` update")
            check(decisive["automerge"] == false) {
                "${presetFile.name} lets a runner label (datasource `github-runners`) merge itself: the last rule " +
                    "that matches one sets automerge=${decisive["automerge"]}. No pull request runs the workflow " +
                    "a runner label matters to most (youndie/sborka#126)."
            }
            val group = decisive["groupName"] as? String
            check(group != null && group != "ci actions") {
                "${presetFile.name} holds the runner label without a group of its own (groupName=$group): in " +
                    "`ci actions` it would hold every action major grouped beside it"
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyGradleVersion)
    dependsOn(verifyHarnessPreset)
}
