// The stand's root exists to hold two things: one command that runs everything, and the check that
// reads what the publish actually produced.

val publishTasks =
    listOf(
        ":jvm-lib:publishAllPublicationsToStandRepository",
        ":kmp-lib:publishAllPublicationsToStandRepository",
        ":platform:publishAllPublicationsToStandRepository",
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
val verifyPublications =
    tasks.register("verifyPublications") {
        group = "verification"
        description = "Checks what actually landed in the stand repository"
        dependsOn(publishTasks)

        val repoDir = layout.buildDirectory.dir("repo")
        val version = project.version.toString()
        val groupPath = "ru/workinprogress/stand"
        outputs.upToDateWhen { false }

        doLast {
            val root = repoDir.get().asFile

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
                    // names carry the FULL version — a jar named after a fallback version under a
                    // coordinate carrying the real one resolves correctly and is still wrong.
                    artefact("kmp-lib", ".module"),
                    artefact("kmp-lib-jvm", ".jar"),
                    artefact("kmp-lib-jvm", ".module"),
                    artefact("kmp-lib-linuxx64", ".module"),
                )

            val missing = required.filterNot { it.isFile }
            check(missing.isEmpty()) {
                "these were not published, though every publish task reported success:\n  " +
                    missing.joinToString("\n  ") { it.relativeTo(root).path }
            }

            // THE POM SAYS WHO OWNS IT. Derived from one property in `gradle.properties` rather than
            // pasted into each repository, and the three scm strings are the part that got pasted
            // wrong.
            val pom = artefact("jvm-lib", ".pom").readText()
            listOf(
                "<url>https://github.com/youndie/sborka</url>",
                "scm:git:git://github.com/youndie/sborka.git",
                "scm:git:ssh://git@github.com/youndie/sborka.git",
            ).forEach { fragment ->
                check(pom.contains(fragment)) { "the published pom does not carry $fragment" }
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

            logger.lifecycle("verifyPublications: ${required.size} artefacts, the pom and the jvm floor all check out")
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
    dependsOn(verifyPublications)
}
