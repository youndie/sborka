// What the three published jars share. Each of them says what it is; this says what they all are.

// Emptied before publishing, or the evidence outlives the run that produced it: a directory
// repository accumulates, and yesterday's artefacts beside today's let the root's check pass on a run
// that published nothing.
val cleanLocalRepo =
    tasks.register<Delete>("cleanLocalRepo") {
        description = "Empties the local plugin repository"
        delete(rootProject.layout.buildDirectory.dir("../../build/local-repo-plugins"))
    }

subprojects {
    apply(plugin = "org.gradle.maven-publish")

    // ON THE INDIVIDUAL PUBLISH TASKS, not on the `publishAllPublicationsTo…` aggregate. The
    // aggregate only DEPENDS ON the real publish tasks, so ordering the aggregate after the delete
    // leaves the delete free to run beside them — which it does under `org.gradle.parallel`, and
    // fails as "unable to delete directory" or, worse, as a publish whose output was removed after
    // it succeeded.
    tasks.withType<org.gradle.api.publish.maven.tasks.PublishToMavenRepository>().configureEach {
        dependsOn(cleanLocalRepo)
    }

    // ktlint is applied in each module's own `plugins { }` block rather than from here, and that is
    // the same classloader story as the three-jar split: applied from a root that has no Kotlin plugin
    // under it, the ktlint plugin fails on `NoClassDefFoundError: KotlinProjectExtension` — it looks
    // Kotlin up at apply time, and finds it only in a loader that has `kotlin-dsl` beside it.

    group = "io.github.youndie.sborka"

    // AND BY THE RULES SBORKA SHIPS, not only by its formatter.
    //
    // `sborka.lint` hands consumers a `ktlintRuleset`; these modules cannot apply `sborka.lint` —
    // sborka does not apply its own conventions, on purpose — so they say the same thing by hand. A
    // rule set nobody runs on the repository that publishes it is a rule set whose first real corpus
    // is somebody else's build.
    //
    // `:kapkan` is in this list too, and depends on itself: `ktlintCheck` needs its jar, and its jar
    // needs nothing from `check`, so there is no cycle — only a module whose rules are the first
    // thing they are pointed at.
    plugins.withId("org.jlleitschuh.gradle.ktlint") {
        dependencies.add("ktlintRuleset", dependencies.project(mapOf("path" to ":kapkan")))
    }

    // ONE NUMBER, and it lives in the root `gradle.properties` beside the catalog's.
    //
    // An included build reads its OWN directory's `gradle.properties` and not its parent's, so the
    // obvious `providers.gradleProperty("sborka.version")` here resolves to nothing — and a second
    // `sborka.version` in this directory would be the same number in two files, which is the number
    // that gets half-changed. `-PVERSION` from CI reaches every build in the tree and still wins.
    val headVersion =
        providers
            .fileContents(rootProject.layout.projectDirectory.file("../gradle.properties"))
            .asText
            .map { text ->
                text
                    .lineSequence()
                    .first { it.startsWith("sborka.version=") }
                    .substringAfter('=')
                    .trim()
            }

    version = providers.gradleProperty("VERSION").orElse(headVersion).get()

    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            // 21, not 25. These jars are loaded by the Gradle daemon of every repository that applies
            // them, and those daemons run on 21 and on 25 today. Compiling for the newer one would
            // fail on the older with UnsupportedClassVersionError — a message naming a bytecode
            // version and not this decision. `kotlin-dsl` takes its Kotlin jvmTarget from here, and
            // the check below reads the bytecode rather than trusting that it did.
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
            withSourcesJar()
        }
    }

    // THE PUBLICATION THAT NOBODY REGISTERS, and sborka shipped a release without it.
    //
    // `kotlin-dsl` brings `java-gradle-plugin`, which registers `pluginMaven` and a marker per plugin
    // — so `:conventions` and `:settings` publish themselves. `:core` has neither: it holds no plugin,
    // so `maven-publish` alone has nothing to upload, and it uploads nothing. The task is green, the
    // job is green, and version 0.1.0.3 went out with `:conventions` and `:settings` declaring an
    // `api` dependency on a `:core` that is not on the server — unresolvable for every consumer.
    //
    // This is the exact defect `sborka.publish` exists to fix, hit by the one build that deliberately
    // cannot apply sborka's own conventions. Found by asking the server what a
    // consumer would get; nothing inside the build could have said it.
    //
    // Guarded now by `verifyBuildLogicPublications` in the root build, which reads the directory the
    // publish wrote instead of trusting its exit code.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        if (!plugins.hasPlugin("java-gradle-plugin")) {
            extensions.configure<PublishingExtension> {
                publications.create<MavenPublication>("maven") {
                    from(components["java"])
                }
            }
        }
    }

    extensions.configure<PublishingExtension> {
        repositories {
            // A directory of its own, not the one `:catalog` writes. Each build cleans the
            // directory it owns before publishing into it, and two builds cleaning one directory
            // while the other writes to it is a race that fails as "unable to delete".
            maven {
                name = "local"
                url = uri(rootProject.layout.buildDirectory.dir("../../build/local-repo-plugins"))
            }
            maven {
                name = "wip"
                url = uri("https://reposilite.kotlin.website/snapshots")
                // /snapshots is readable anonymously; credentials are needed only for writing, and
                // they come from the environment — so a checkout of this repository builds and tests
                // but cannot publish.
                credentials {
                    username = providers.gradleProperty("REPOSILITE_USER").orNull
                    password = providers.gradleProperty("REPOSILITE_SECRET").orNull
                }
            }
        }
    }
}

// Aggregates, so the build that includes this one has a single task to depend on. An included build
// is addressed by its ROOT project, and a root project holding no code has no `check` of its own.
listOf(
    "check",
    "publishAllPublicationsToWipRepository",
    "publishAllPublicationsToLocalRepository",
).forEach { aggregate ->
    tasks.register(aggregate) {
        group = if (aggregate == "check") "verification" else "publishing"
        description = "Runs $aggregate in every module of build-logic"
        dependsOn(subprojects.map { "${it.path}:$aggregate" })
    }
}
