// What the three published jars share. Each of them says what it is; this says what they all are.

subprojects {
    apply(plugin = "org.gradle.maven-publish")

    // ktlint is applied in each module's own `plugins { }` block rather than from here, and that is
    // the same classloader story as the three-jar split: applied from a root that has no Kotlin plugin
    // under it, the ktlint plugin fails on `NoClassDefFoundError: KotlinProjectExtension` — it looks
    // Kotlin up at apply time, and finds it only in a loader that has `kotlin-dsl` beside it.

    group = "ru.workinprogress.sborka"

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

    extensions.configure<PublishingExtension> {
        repositories {
            maven {
                name = "local"
                url = uri(rootProject.layout.buildDirectory.dir("../../build/local-repo"))
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
