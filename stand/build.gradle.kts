// The stand's root exists to hold two things: one command that runs everything, and the check that
// reads what the publish actually produced.

// ONE KOTLIN PLUGIN FOR THE WHOLE BUILD, applied nowhere and resolved once.
//
// Without this, each module that names Kotlin gets its own classloader scope, and the second one to
// declare a native target fails with "cannot set the value of property kotlinNativeBundleBuildService
// ... loaded with InstrumentingVisitableURLClassLoader" - two copies of one build service class,
// named by neither module. `apply false` at the root is the arrangement that gives them one.
plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    // The jvm half of the same jar, for the same scope: requested by a module with a version while
    // the root already carries one, Gradle refuses with "already on the classpath with an unknown
    // version" rather than picking either.
    alias(libs.plugins.kotlinJvm) apply false
}

val publishTasks =
    listOf(
        ":jvm-lib:publishAllPublicationsToStandRepository",
        ":kmp-lib:publishAllPublicationsToStandRepository",
        ":platform:publishAllPublicationsToStandRepository",
        ":gradle-plugin:publishAllPublicationsToStandRepository",
    )

/**
 * READS THE REPOSITORY AFTER THE UPLOAD, rather than trusting that the upload happened.
 *
 * This is the whole reason the stand exists. A `kotlin("jvm")` module with no registered publication
 * builds fine, its publish task reports success, and it uploads nothing — Gradle's exit code cannot
 * tell that apart from a publish that worked. Neither can a human reading the log. Only looking at
 * what arrived can, and every gap the publish convention closes was found exactly this way, in a real
 * repository, long after a green build.
 */
// EMPTIED BEFORE EVERY RUN, or the evidence outlives the run that produced it.
//
// A directory repository accumulates. Yesterday's artefacts sitting beside today's would let this
// check pass on a run that published nothing at all — and the version-discovery above would see two
// versions and blame the convention. On CI the checkout is fresh and neither happens, which is
// exactly why it has to be arranged here: a check that only works on a machine that has never run it
// before is a check that stops working the moment anyone uses it.
val cleanStandRepo =
    tasks.register<Delete>("cleanStandRepo") {
        description = "Empties the stand repository so a run cannot pass on last run's artefacts"
        delete(layout.buildDirectory.dir("repo"))
    }

subprojects {
    // ON THE INDIVIDUAL PUBLISH TASKS, not on the `publishAllPublicationsTo…` aggregate. The
    // aggregate only DEPENDS ON the real publish tasks, so ordering the aggregate after the delete
    // leaves the delete free to run beside them — which it does under `org.gradle.parallel`, and
    // fails as "unable to delete directory" or, worse, as a publish whose output was removed after
    // it succeeded.
    tasks.withType<org.gradle.api.publish.maven.tasks.PublishToMavenRepository>().configureEach {
        dependsOn(cleanStandRepo)
    }
}

val verifyPublications =
    tasks.register("verifyPublications") {
        group = "verification"
        description = "Checks what actually landed in the stand repository"
        dependsOn(publishTasks)

        val repoDir = layout.buildDirectory.dir("repo")
        val groupPath = "io/github/youndie/stand"

        // WHAT THE VERSION IS, ACCORDING TO THE PUBLISH RATHER THAN ACCORDING TO THIS TASK.
        //
        // The first version of this took the root project's version and built the expected paths from
        // it. That is the same number in two places, and CI found the second one: the publish workflow
        // passes `-PVERSION`, `sborka.base` puts it on every MODULE, and the stand's root — which
        // applies no convention — kept the head from `gradle.properties`. Every artefact then read as
        // missing, in a run that had published all of them correctly.
        //
        // So the version is DISCOVERED from what arrived, and the expectation is checked against it
        // separately below. That also catches something the arithmetic could not: two versions in the
        // directory at once.
        val expectedVersion = providers.gradleProperty("VERSION").orNull
        val central = providers.gradleProperty("sborka.central").orNull.toBoolean()
        outputs.upToDateWhen { false }

        doLast {
            val root = repoDir.get().asFile
            val moduleNames = listOf("jvm-lib", "platform", "kmp-lib", "kmp-lib-jvm", "kmp-lib-linuxx64")

            val versionsPerModule =
                moduleNames.associateWith { module ->
                    File(root, "$groupPath/$module")
                        .listFiles()
                        .orEmpty()
                        .filter { it.isDirectory }
                        .map { it.name }
                        .sorted()
                }

            val absent = versionsPerModule.filterValues { it.isEmpty() }.keys
            check(absent.isEmpty()) {
                "these modules published nothing at all, though every publish task reported success: " +
                    absent.sorted().joinToString()
            }

            val versions = versionsPerModule.values.flatten().distinct()
            check(versions.size == 1) {
                "the modules landed under more than one version — $versions. Two releases in one " +
                    "directory is what a version set on the publication but not on the project looks " +
                    "like from the outside."
            }
            val version = versions.single()

            if (expectedVersion != null) {
                check(version == expectedVersion) {
                    "-PVERSION was $expectedVersion and the artefacts arrived as $version. The archive " +
                        "tasks take their file names from the PROJECT version, so a version set on the " +
                        "publication alone ships files named after the fallback under the coordinate " +
                        "carrying the real one."
                }
            }

            fun artefact(
                module: String,
                suffix: String,
            ) = File(root, "$groupPath/$module/$version/$module-$version$suffix")

            val required =
                listOf(
                    // The plain Kotlin/JVM module: the shape that publishes nothing when the
                    // convention forgets it.
                    artefact("jvm-lib", ".jar"),
                    artefact("jvm-lib", ".pom"),
                    artefact("jvm-lib", "-sources.jar"),
                    // The platform: no sources to give away, only the component carrying constraints.
                    artefact("platform", ".pom"),
                    // The multiplatform module: a root publication plus one per target, and the file
                    // names carry the FULL version.
                    artefact("kmp-lib", ".module"),
                    artefact("kmp-lib-jvm", ".jar"),
                    artefact("kmp-lib-jvm", ".module"),
                    artefact("kmp-lib-linuxx64", ".module"),
                )

            // WHAT MAVEN CENTRAL ADDS TO THE LIST, and the only reason `sborka.central` exists.
            //
            // A Reposilite takes whatever it is given. Central refuses a release bundle that has no
            // javadoc jar beside the sources jar, and it refuses it at the portal — after the
            // version has been decided, tagged and published everywhere else, and with no way to
            // reuse the number. `withSourcesJar()` alone produced exactly four files (jar, sources,
            // module, pom); this is the difference, checked here rather than found there.
            //
            // Not the platform: a `java-platform` publishes constraints and no artefacts at all, and
            // Central asks for none.
            val forCentral =
                if (!central) {
                    emptyList()
                } else {
                    listOf(
                        artefact("jvm-lib", "-javadoc.jar"),
                        artefact("kmp-lib", "-javadoc.jar"),
                        artefact("kmp-lib-jvm", "-javadoc.jar"),
                    )
                }

            val missing = (required + forCentral).filterNot { it.isFile }
            check(missing.isEmpty()) {
                "these were not published, though every publish task reported success:\n  " +
                    missing.joinToString("\n  ") { it.relativeTo(root).path } +
                    if (missing.any { it.name.endsWith("-javadoc.jar") }) {
                        "\n(sborka.central is on, so a javadoc jar is part of the shape: Central " +
                            "refuses a bundle without one, and refuses it after the version is spent)"
                    } else {
                        ""
                    }
            }

            // THE POM SAYS WHO OWNS IT. Derived from one property in `gradle.properties` rather than
            // pasted into every repository, and the three scm strings are the part that got pasted
            // wrong.
            val pom = artefact("jvm-lib", ".pom").readText()
            listOf(
                "<url>https://github.com/youndie/sborka</url>",
                "scm:git:git://github.com/youndie/sborka.git",
                "scm:git:ssh://git@github.com/youndie/sborka.git",
                // The developer's own url, which is built from a DIFFERENT value than the three above
                // and was the one that was wrong.
                "<url>https://github.com/youndie</url>",
            ).forEach { fragment ->
                check(pom.contains(fragment)) { "the published pom does not carry $fragment" }
            }

            // AND NOTHING IN IT MAY LOOK LIKE A PROVIDER THAT WAS PRINTED INSTEAD OF READ.
            //
            // `"https://github.com/$developerId"` compiles, publishes, and puts
            // `or(provider(?), fixed(youndie))` in the pom: `Provider.toString()` is a description of
            // how the value would be computed. The fragments above only cover the strings somebody
            // thought to list, so this covers the shape instead — every pom, every field.
            listOf("provider(", "fixed(", "property(", "Provider<").forEach { leak ->
                check(!pom.contains(leak)) {
                    "the published pom contains '$leak' — a Provider was interpolated into a string " +
                        "somewhere instead of being read, and what shipped is its description rather " +
                        "than its value:\n" + pom.lines().filter { it.contains(leak) }.joinToString("\n")
                }
            }

            // THE JVM FLOOR IS IN THE METADATA, on the variant a consumer actually takes.
            //
            // A multiplatform module publishes its jvm variants with no `org.gradle.jvm.version` at
            // all unless something puts it there, and without it Gradle has nothing to refuse a
            // too-old consumer with: resolution succeeds, compilation succeeds, and the failure
            // arrives at class loading as UnsupportedClassVersionError.
            val jvmModule = artefact("kmp-lib-jvm", ".module").readText()
            check(jvmModule.contains("\"org.gradle.jvm.version\": 17")) {
                "the kmp-lib jvm variant carries no org.gradle.jvm.version = 17. Published metadata " +
                    "without it lets a consumer below the floor resolve this library and fail at class " +
                    "loading, with a message naming a bytecode version rather than the library."
            }

            // AND THE BYTECODE ITSELF, not only what the metadata claims about it.
            //
            // The attribute and the class files are set by different things and can disagree: a
            // module on a toolchain of 21 with a floor of 17 advertised 17 and shipped class file 65,
            // which needs Java 21. Gradle lets the consumer through — the attribute says they are
            // welcome — and the failure arrives at class loading as UnsupportedClassVersionError,
            // naming a class file version and nothing about the library. Every machine that builds it
            // is too new to see it.
            //
            // Java 17 is class file 61, and every release since is one more.
            val expectedClassFile = 17 + 44
            val jars =
                listOf(artefact("jvm-lib", ".jar"), artefact("kmp-lib-jvm", ".jar"))
            var classesRead = 0
            val tooNew = mutableListOf<String>()
            jars.forEach { jar ->
                java.util.zip.ZipFile(jar).use { archive ->
                    val classes = archive.entries().toList().filter { it.name.endsWith(".class") }
                    classes.forEach { entry ->
                        classesRead++
                        val header = archive.getInputStream(entry).use { it.readNBytes(8) }
                        val major = ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
                        if (major != expectedClassFile) {
                            tooNew += "${jar.name}!${entry.name}: class file $major, expected $expectedClassFile"
                        }
                    }
                }
            }
            // A run that read no class files would pass every comparison below by finding nothing.
            check(classesRead > 0) { "no class files were read out of $jars — the check proved nothing" }
            check(tooNew.isEmpty()) {
                "the bytecode does not match the floor the metadata advertises:\n  " +
                    tooNew.joinToString("\n  ")
            }

            logger.lifecycle(
                "verifyPublications: ${required.size} artefacts at $version, the pom, the jvm floor " +
                    "and $classesRead class files at Java 17",
            )
        }
    }

tasks.register("check") {
    group = "verification"
    description = "Runs every module's checks and reads what the publish produced"
    dependsOn(subprojects.map { "${it.path}:check" })

    // AND the tasks the conventions REGISTER, which `check` does not reach on its own. A task that is
    // registered and never executed is the shape of defect these conventions exist to catch: it
    // compiles, it appears in `tasks`, and it fails the first time somebody runs it.
    dependsOn(":jvm-lib:mutationTest")
    dependsOn(":gradle-plugin:verifyPublicationShape")
    dependsOn(verifyPublications)
}
