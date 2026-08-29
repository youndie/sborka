package ru.workinprogress.sborka.internal

import org.gradle.api.Project

/**
 * The knobs a repository turns, read in one place and named in one place.
 *
 * Every one of them is a Gradle property, which means `gradle.properties` at the root of the
 * consuming repository — a file a person edits and a diff shows — rather than a call repeated in
 * each module. The portfolio's own count is the argument: `jvmToolchain(n)` appears sixty times
 * across eighteen repositories, and the version of a repository's group id appears once per module
 * in four of them.
 *
 * Not an extension object: an extension is configured in a build script, and a build script is
 * exactly the place these were escaping from.
 */
object SborkaSettings {
    /**
     * The Maven group every module of the repository publishes under.
     *
     * Required, with no default. A default here would be a coordinate nobody chose: six modules of
     * one repository were once published under a group derived from the root project's directory
     * name, and the failure surfaced at upload time as a PUT to the wrong path, after everything
     * had compiled and tested.
     */
    fun group(project: Project): String =
        project.providers.gradleProperty("sborka.group").orNull
            ?: error(
                "sborka.group is not set. Add it to gradle.properties at the root of the repository, " +
                    "for example `sborka.group=ru.workinprogress.viddik`. It is deliberately not " +
                    "defaulted: a group nobody chose resolves, publishes, and lands under the wrong " +
                    "coordinate.",
            )

    /**
     * The version, resolved once and set on the PROJECT.
     *
     * `-PVERSION` first, which is what CI passes; then whatever `gradle.properties` gave `version`,
     * which is what a local build and `publishToMavenLocal` use; then a snapshot, so a build with
     * neither cannot produce something that looks like a release.
     *
     * SETTING THE PUBLICATION VERSION ALONE IS NOT ENOUGH, and the difference is invisible from
     * inside the build: the archive tasks take their file names from the project version, so a
     * publication carrying the full number ships files named after the fallback. Resolution works —
     * the metadata points at the right url — and the file is merely misnamed on arrival, which makes
     * two releases indistinguishable to anything downstream that reads file names.
     */
    fun version(project: Project): String {
        val fromCi = project.providers.gradleProperty("VERSION").orNull
        if (fromCi != null) return fromCi
        val fromProperties = project.version.toString()
        if (fromProperties.isNotBlank() && fromProperties != "unspecified") return fromProperties
        return "0.1.0-SNAPSHOT"
    }

    /** The JDK every module compiles with. One number per repository, in `gradle.properties`. */
    fun jvmToolchain(project: Project): Int = project.intProperty("sborka.jvmToolchain", default = 21)

    /**
     * The oldest Java a consumer may be on, said out loud in the published metadata.
     *
     * Not the same question as the toolchain, and defaulted lower on purpose: the JDK that builds a
     * library is not the JDK that has to run it. A repository that raises this without meaning to
     * takes the ability to use the library away from every consumer below the new floor — and finds
     * out from them.
     */
    fun jvmFloor(project: Project): Int = project.intProperty("sborka.jvmFloor", default = 17)

    /** `owner/name` on GitHub. The url, the licence link and the scm block are derived from it. */
    fun repository(project: Project): String? = project.providers.gradleProperty("sborka.repository").orNull

    /** Where snapshots go. Overridable so a fork or a mirror does not have to patch the plugin. */
    fun snapshotRepository(project: Project): String =
        project.providers.gradleProperty("sborka.snapshotRepository").orNull
            ?: "https://reposilite.kotlin.website/snapshots"

    fun flag(
        project: Project,
        name: String,
        default: Boolean,
    ): Boolean =
        project.providers
            .gradleProperty(name)
            .orNull
            ?.toBooleanStrictOrNull() ?: default

    private fun Project.intProperty(
        name: String,
        default: Int,
    ): Int {
        val raw = providers.gradleProperty(name).orNull ?: return default
        return raw.trim().toIntOrNull()
            ?: error("$name must be a whole number, got \"$raw\"")
    }
}
