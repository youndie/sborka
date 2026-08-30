import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

plugins {
    alias(libs.plugins.ktlint)
}

// The root's own scripts. `:catalog` applies the plugin itself — applied from here it would be loaded
// by a classloader with no Kotlin plugin under it, and the ktlint plugin looks Kotlin up at apply time.
ktlint {
    version.set(libs.versions.ktlintTool)
}

// The root holds no code. Everything sborka publishes lives in the `build-logic` build and in `:catalog`;
// what is here is the one task that runs everything.

// WHAT SBORKA ITSELF PUBLISHES, read off the directory rather than off an exit code.
//
// Version 0.1.0.3 went out with `:core` missing. `kotlin-dsl` brings `java-gradle-plugin`, which
// registers the publications for `:conventions` and `:settings`; `:core` holds no plugin, so
// `maven-publish` had nothing to upload and uploaded nothing — green task, green job, and two jars on
// the server declaring an `api` dependency on a coordinate that is not there.
//
// The stand catches this shape for a consuming repository. It could not catch it here: sborka does not
// apply its own conventions, on purpose, so `build-logic` publishes by hand and had the defect by
// hand. Hence a second reader, of the same kind, pointed at sborka's own output.
// Emptied first, or the evidence outlives the run that produced it: a directory repository
// accumulates, and yesterday's artefacts beside today's let this pass on a run that published nothing.
val cleanLocalRepo =
    tasks.register<Delete>("cleanLocalRepo") {
        description = "Empties the local repository so a run cannot pass on the last run's artefacts"
        delete(layout.buildDirectory.dir("local-repo"))
    }

subprojects {
    // ON THE INDIVIDUAL PUBLISH TASKS, not on the `publishAllPublicationsTo…` aggregate. The
    // aggregate only DEPENDS ON the real publish tasks, so ordering the aggregate after the delete
    // leaves the delete free to run beside them — which it does under `org.gradle.parallel`, and
    // fails as "unable to delete directory" or, worse, as a publish whose output was removed after
    // it succeeded.
    tasks.withType<PublishToMavenRepository>().configureEach {
        dependsOn(cleanLocalRepo)
    }
}

val verifyBuildLogicPublications =
    tasks.register("verifyBuildLogicPublications") {
        group = "verification"
        description = "Checks what sborka's own publish actually wrote"
        dependsOn(gradle.includedBuild("build-logic").task(":publishAllPublicationsToLocalRepository"))
        dependsOn(":catalog:publishAllPublicationsToLocalRepository")
        dependsOn(cleanLocalRepo)

        // Two directories, because two builds write them and each cleans its own.
        val pluginRepoDir = layout.buildDirectory.dir("local-repo-plugins")
        val catalogRepoDir = layout.buildDirectory.dir("local-repo")
        val expectedVersion = providers.gradleProperty("VERSION").orNull
        val pluginIds =
            listOf("base", "lint", "test", "publish", "jvm", "kmp", "mutation", "native-service", "settings")
        outputs.upToDateWhen { false }

        doLast {
            val catalogGroupDir = File(catalogRepoDir.get().asFile, "ru/workinprogress/sborka")
            val root = pluginRepoDir.get().asFile
            val groupDir = File(root, "ru/workinprogress/sborka")

            // A VERSION DIRECTORY STARTS WITH A DIGIT, and the filter is not decoration. `settings` is
            // both a module name and a plugin id, so `.../sborka/settings/` holds the version
            // directories AND `ru.workinprogress.sborka.settings.gradle.plugin` beside them — which the
            // "more than one version" check below duly reported as a second version.
            fun versionsOf(module: String) =
                File(if (module == "catalog") catalogGroupDir else groupDir, module)
                    .listFiles()
                    .orEmpty()
                    .filter { it.isDirectory && it.name.first().isDigit() }
                    .map { it.name }

            val libraries = listOf("core", "conventions", "settings", "catalog")
            val absent = libraries.filter { versionsOf(it).isEmpty() }
            check(absent.isEmpty()) {
                "these published nothing, though every publish task reported success: ${absent.joinToString()}. " +
                    "A module with no registered publication has nothing to upload, and an exit code " +
                    "cannot tell that apart from an upload."
            }

            val versions = libraries.flatMap(::versionsOf).distinct()
            check(versions.size == 1) { "the modules landed under more than one version — $versions" }
            val version = versions.single()
            if (expectedVersion != null) {
                check(version == expectedVersion) {
                    "-PVERSION was $expectedVersion and the artefacts arrived as $version"
                }
            }

            val required =
                listOf(
                    File(groupDir, "core/$version/core-$version.jar"),
                    File(groupDir, "core/$version/core-$version.pom"),
                    File(groupDir, "conventions/$version/conventions-$version.jar"),
                    File(groupDir, "settings/$version/settings-$version.jar"),
                    File(catalogGroupDir, "catalog/$version/catalog-$version.toml"),
                ) +
                    // THE MARKERS, one per plugin id. A marker is how `plugins { id(...) version ... }`
                    // finds the jar at all: without it the conventions are on the server and
                    // unreachable by the only means anybody uses to ask for them.
                    pluginIds.map { id ->
                        val marker = "ru.workinprogress.sborka.$id.gradle.plugin"
                        File(groupDir, "$id/$marker/$version/$marker-$version.pom")
                    }

            val missing = required.filterNot { it.isFile }
            check(missing.isEmpty()) {
                "these were not written:\n  " + missing.joinToString("\n  ") { it.relativeTo(root).path }
            }

            // EVERY DEPENDENCY THIS RELEASE DECLARES MUST BE PART OF IT. `:conventions` and
            // `:settings` carry `api(projects.core)`, and that is the edge 0.1.0.3 broke.
            listOf("conventions", "settings").forEach { module ->
                val pom = File(groupDir, "$module/$version/$module-$version.pom").readText()
                if (pom.contains("<artifactId>core</artifactId>")) {
                    check(File(groupDir, "core/$version/core-$version.jar").isFile) {
                        "$module declares a dependency on core:$version, which this release does not contain"
                    }
                }
            }

            logger.lifecycle(
                "verifyBuildLogicPublications: ${required.size} artefacts at $version, " +
                    "${pluginIds.size} plugin markers, and no dangling core edge",
            )
        }
    }

// Compiles the plugins, runs their unit tests, and then APPLIES them in a real build.
//
// The second half is the part that cannot be skipped. Compiling a convention plugin says nothing about
// whether it can be applied: an id derived from the wrong package, a `compileOnly` dependency that
// turns out to be needed at execution time, a task registered under a name nothing produces — none of
// that is a compilation error.
//
// Named `check` so `./gradlew check` at the root runs all of it. A gate that has to be remembered as a
// second command is not a gate.
tasks.register("check") {
    group = "verification"
    description = "Builds the conventions and applies them in a separate build"
    // The root has no `base` plugin, so ktlint's task is not wired into anything on its own.
    dependsOn(tasks.named("ktlintCheck"))
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
    dependsOn(verifyBuildLogicPublications)
    dependsOn(gradle.includedBuild("stand").task(":check"))
}

tasks.register("publishToWip") {
    group = "publishing"
    description = "Publishes the conventions and the version catalog to the wip snapshot repository"
    dependsOn(gradle.includedBuild("build-logic").task(":publishAllPublicationsToWipRepository"))
    dependsOn(":catalog:publishAllPublicationsToWipRepository")
}
