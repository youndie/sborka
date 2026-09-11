package io.github.youndie.sborka.internal

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
    fun group(project: Project): String {
        val declared = project.providers.gradleProperty("sborka.group").orNull
        if (declared != null) return declared

        // NOT WHILE GRADLE IS GENERATING ACCESSORS. A repository that wraps these conventions in its
        // own `build-logic` — one does, because its target sets are its own and its releases go to
        // Maven Central — has that build APPLY this plugin to a throwaway project to work out which
        // accessors its scripts need. That project has no `gradle.properties` and never publishes
        // anything, so failing there refuses a legitimate arrangement over a property that will be
        // present everywhere the plugin is really used.
        //
        // Detected by the name Gradle gives the throwaway, which is the only thing that distinguishes
        // it. If that name ever changes, this stops skipping and starts failing — loudly, in a build
        // that has nothing wrong with it, which is the failure direction to prefer.
        if (project.rootProject.name == "gradle-kotlin-dsl-accessors") return "gradle-kotlin-dsl-accessors"

        error(
            "sborka.group is not set. Add it to gradle.properties at the root of the repository, " +
                "for example `sborka.group=io.github.youndie.mylib`. It is deliberately not " +
                "defaulted: a group nobody chose resolves, publishes, and lands under the wrong " +
                "coordinate. " +
                "(asked for by project '${project.path}' of build '${project.rootProject.name}')",
        )
    }

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

    /**
     * Where snapshots go. Overridable so a fork or a mirror does not have to patch the plugin.
     *
     * An override has to be an absolute http(s) URL, and this refuses anything else rather than
     * publishing somewhere nobody can resolve from. Gradle's `uri()` accepts any string: one that is
     * not absolute becomes a **relative file path**, the repository quietly turns into a `file:` one,
     * and the publish either writes into the build directory or fails with "Authentication scheme
     * 'all' is not supported by protocol 'file'" — a message about authentication, for a problem that
     * is a wrong address.
     *
     * Carried over from a repository that wrote it after `gh secret set --body -` set the secret to
     * the literal string `-` (that flag does not read standard input). The fallback never fires for a
     * value like that: empty-by-mistake is not the same as absent, which is why this refuses rather
     * than defaults.
     */
    fun snapshotRepository(project: Project): String {
        val configured =
            project.providers.gradleProperty("sborka.snapshotRepository").orNull
                ?: return "https://reposilite.kotlin.website/snapshots"
        require(configured.startsWith("http://") || configured.startsWith("https://")) {
            "sborka.snapshotRepository must be an absolute http(s) URL; got '$configured'. " +
                "Anything else becomes a file: repository and publishes nowhere anybody can resolve from."
        }
        return configured
    }

    /**
     * The size budget for this module's binary, in bytes, or null when the repository has not set one.
     *
     * Written as `sborka.binaryBudget=50MiB`; [ByteSize] is what refuses anything else. Absent means
     * absent - no gate, no task, no cost - because a budget that arrives with a dependency bump is a
     * gate people switch off before they read it.
     */
    fun binaryBudget(project: Project): Long? =
        project.providers
            .gradleProperty(BINARY_BUDGET)
            .orNull
            ?.let { ByteSize.parse(it, BINARY_BUDGET) }

    const val BINARY_BUDGET: String = "sborka.binaryBudget"

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
