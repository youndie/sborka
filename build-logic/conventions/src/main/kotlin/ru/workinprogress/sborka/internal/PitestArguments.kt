package ru.workinprogress.sborka.internal

import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.ListProperty
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
    @get:Input val forkJvmArgs: ListProperty<String>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> {
        val fork = forkJvmArgs.get()
        // pitest joins these with commas and splits them the same way, so a value carrying one is
        // silently read as two arguments. Refused here, where the value is still identifiable, rather
        // than by a minion failing to start.
        val withComma = fork.filter { it.contains(',') }
        require(withComma.isEmpty()) {
            "sborkaMutation.forkJvmArgs may not contain a comma — pitest separates the minion's " +
                "arguments on commas, so these would reach it as more arguments than were written: " +
                withComma.joinToString()
        }
        return listOf(
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
        ) + if (fork.isEmpty()) emptyList() else listOf("--jvmArgs", fork.joinToString(","))
    }
}
