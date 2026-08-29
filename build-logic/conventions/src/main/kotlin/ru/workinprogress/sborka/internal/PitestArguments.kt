package ru.workinprogress.sborka.internal

import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

/**
 * pitest's command line, as a class rather than as a lambda in the convention script.
 *
 * A `CommandLineArgumentProvider { … }` written inline captures the script object, and the
 * configuration cache refuses to serialise one: the build fails with "cannot serialize Gradle script
 * object references", which names the mechanism and not the mistake. The same shape of problem takes
 * out a captured `SourceSet` — hence file collections here, and no Gradle model objects at all.
 *
 * The annotations are not decoration. Without them the arguments are outside the task's input
 * snapshot, and a run stays up to date after the code it mutates has changed — a mutation report that
 * describes a build from yesterday.
 */
class PitestArguments(
    @get:Internal val reportDir: Provider<Directory>,
    @get:Input val targetClasses: Provider<String>,
    @get:Input val targetTests: String,
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) val sourceDirs: FileCollection,
    @get:InputFiles val mutableCodePaths: FileCollection,
    @get:InputFiles val runtimeClasspath: FileCollection,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf(
            "--reportDir",
            reportDir.get().asFile.absolutePath,
            "--targetClasses",
            targetClasses.get(),
            "--targetTests",
            targetTests,
            "--sourceDirs",
            sourceDirs.joinToString(",") { it.absolutePath },
            "--mutableCodePaths",
            mutableCodePaths.joinToString(",") { it.absolutePath },
            "--classPath",
            runtimeClasspath.joinToString(",") { it.absolutePath },
            "--testPlugin",
            "junit5",
            "--outputFormats",
            "HTML,XML",
            "--timestampedReports",
            "false",
            "--threads",
            Runtime.getRuntime().availableProcessors().toString(),
        )
}
