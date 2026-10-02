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
// would notice if they stopped: the Renovate preset, whose wrapper rule decides which Gradle every
// repository is offered, and this repository's own wrapper. A preset still holding the old line would
// quietly keep the portfolio there while the catalog claimed otherwise.
val verifyGradleVersion by tasks.registering {
    val catalogFile = layout.projectDirectory.file("sborka.versions.toml").asFile
    val presetFile = rootProject.layout.projectDirectory.file("default.json").asFile
    val wrapperFile =
        rootProject.layout.projectDirectory
            .file("gradle/wrapper/gradle-wrapper.properties")
            .asFile
    inputs.files(catalogFile, presetFile, wrapperFile)
    doLast {
        val declared =
            Regex("""(?m)^gradle\s*=\s*"([^"]+)"""").find(catalogFile.readText())?.groupValues?.get(1)
                ?: error("${catalogFile.name} declares no `gradle` version")

        val preset = groovy.json.JsonSlurper().parse(presetFile) as Map<*, *>
        val wrapperRule =
            (preset["packageRules"] as List<*>)
                .filterIsInstance<Map<*, *>>()
                .lastOrNull { rule ->
                    (rule["matchManagers"] as? List<*>)?.contains("gradle-wrapper") == true &&
                        rule["allowedVersions"] != null
                }
                ?: error("${presetFile.name} has no allowedVersions rule for gradle-wrapper")
        val allowed = (wrapperRule["allowedVersions"] as String).removePrefix("/").removeSuffix("/")
        check(Regex(allowed).containsMatchIn(declared)) {
            "${presetFile.name} allows Gradle /$allowed/ for the wrapper, " +
                "which does not admit $declared from ${catalogFile.name}"
        }

        check("gradle-$declared-" in wrapperFile.readText()) {
            "sborka's own wrapper does not name Gradle $declared, the version ${catalogFile.name} declares"
        }
    }
}

tasks.named("check") { dependsOn(verifyGradleVersion) }
