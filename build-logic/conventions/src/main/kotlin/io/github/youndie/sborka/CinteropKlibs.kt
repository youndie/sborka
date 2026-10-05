package io.github.youndie.sborka

import io.github.youndie.sborka.internal.CinteropLink
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** One cinterop klib of one target, as [VerifyCinteropKlibs] reads it. */
public abstract class CinteropKlib {
    /** `<kotlin target> <interop>`, for the report. */
    @get:Input
    public abstract val label: Property<String>

    /** The konan target's name, `linux_x64`: the `.def` key suffix `headers.linux_x64`. */
    @get:Input
    public abstract val konanTarget: Property<String>

    /** The konan target's family, `linux`: the `.def` key suffix `headers.linux`. */
    @get:Input
    public abstract val family: Property<String>

    /** The klib the interop task produced: a `.klib` file, or a directory when it is unpacked. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val klib: ConfigurableFileCollection

    /** The `.def` the klib was produced from, when there is one. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val definitionFile: RegularFileProperty

    /** Headers named in Gradle (`headers(…)`) rather than in the `.def`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val gradleHeaders: ConfigurableFileCollection
}

/**
 * Reads every cinterop klib the module produces and says what each carries for a consumer's link —
 * and fails on one that declares headers and carries nothing (sborka#116).
 *
 * The list it prints matters as much as the refusal: a second copy of OpenSSL shows up here as one
 * line, before it shows up as `duplicate symbol` in somebody else's link (kafkakn B-98).
 *
 * No outputs, so it runs on every `check`: reading a few zip directories costs nothing, and a report
 * that is printed only when something changed is a report nobody sees.
 */
@DisableCachingByDefault(
    because = "It reads a few zip directories and prints what it found; there is nothing to cache.",
)
public abstract class VerifyCinteropKlibs : DefaultTask() {
    @get:Nested
    public abstract val klibs: ListProperty<CinteropKlib>

    @TaskAction
    public fun verify() {
        val refused = mutableListOf<String>()
        klibs.get().forEach { entry ->
            val klib = entry.klib.files.singleOrNull { it.exists() }
            if (klib == null) {
                // The interop task was skipped: a target this host cannot run cinterop for. Said, so
                // that a report missing a target names the reason.
                logger.lifecycle("  ${entry.label.get()}: not produced on this host")
                return@forEach
            }
            val definition = entry.definitionFile.orNull?.asFile
            val definitionText = definition?.takeIf { it.isFile }?.readText().orEmpty()
            val carried = CinteropLink.read(klib, entry.konanTarget.get(), entry.family.get())
            logger.lifecycle("  ${entry.label.get()} (${klib.name}): ${CinteropLink.describe(carried)}")
            val reason =
                CinteropLink.verdict(
                    declaresHeaders =
                        CinteropLink.declaresHeaders(definitionText, entry.konanTarget.get(), entry.family.get()) ||
                            !entry.gradleHeaders.isEmpty,
                    linksNothingBecause = CinteropLink.linksNothingBecause(definitionText),
                    carried = carried,
                )
            if (reason != null) {
                refused += "${entry.label.get()} (${definition?.path ?: "no .def"}): $reason"
            }
        }
        if (refused.isNotEmpty()) {
            throw GradleException(
                "a cinterop klib that a consumer cannot link:\n" + refused.joinToString("\n") { "- $it" },
            )
        }
    }
}
