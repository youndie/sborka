package io.github.youndie.sborka.internal

/**
 * The three decisions behind wiring a KSP processor that runs **once, over common metadata**.
 *
 * A multiplatform module whose annotated types live in `commonMain` has exactly one sensible place
 * to run a processor: `kspCommonMainMetadata`. Per-target output lands in a platform source set,
 * where the metadata a consumer's `commonMain` compiles against can never see it — a single-target
 * consumer never notices, and adding a second target to one turns it into an unresolved reference.
 *
 * Saying that to Gradle takes three paragraphs of build script, and they were byte-identical in
 * seven modules of one repository. The cost was measured rather than guessed: a race between KSP and
 * dokka over the same directory (youndie/kompot B-33) had to be fixed in seven files with one edit,
 * and missing one of them would have left the flake alive.
 *
 * The rules live here rather than inline in `kmp.gradle.kts` because they are string matching over
 * task names, which is the part that can be wrong in a way a green build does not show.
 */
object KspMetadataWiring {
    /** Where KSP puts what it generated for `commonMain`, relative to the module. */
    const val GENERATED_SOURCES: String = "build/generated/ksp/metadata/commonMain/kotlin"

    /** The one task that generates them. */
    const val GENERATOR: String = "kspCommonMainKotlinMetadata"

    /** The configuration a module puts its processor on to be wired by this convention. */
    const val PROCESSOR_CONFIGURATION: String = "kspCommonMainMetadata"

    /**
     * Whether a task takes the generated sources out of the source set **as paths** and therefore
     * has to be told to wait — the collection Gradle would carry the dependency on is not what these
     * read.
     *
     * A list of name shapes is incomplete by nature, and its incompleteness is what this comment is
     * for. It named two kinds for a year: things that compile, and the sources jar. Dokka was a
     * third. It reads the same directory, matched neither shape, and therefore ran CONCURRENTLY with
     * KSP — which wipes its output directory before regenerating it. The symptom was a publish
     * failing with `FileNotFoundException` on a file that does exist, once in every few runs, on
     * branches that had not touched the module.
     *
     * ktlint is the fourth, and it was found the first time this wiring was applied to a module that
     * also takes `sborka.lint` — which the repository the list came from does not. Gradle refused it
     * outright there (`uses this output of task ... without declaring an explicit or implicit
     * dependency`) rather than racing, because a ktlint task declares the source set as an input;
     * the `/build/generated/` exclusion `sborka.lint` carries filters what is CHECKED and not what
     * is declared. A fifth kind will arrive the same way.
     *
     * The honest fix would be `builtBy` on the source directory, and it is not available: KSP reads
     * the very source set it writes into, so making the source set depend on the producer makes the
     * producer depend on itself, and Gradle answers with a circular dependency between
     * `kspCommonMainKotlinMetadata` and itself. That was tried; this list is what is left.
     */
    fun readsGeneratedSources(taskName: String): Boolean =
        taskName != GENERATOR &&
            (
                taskName.startsWith("compile") ||
                    taskName.startsWith("dokka") ||
                    taskName.startsWith("ktlint") ||
                    taskName.startsWith("runKtlint") ||
                    taskName.lowercase().endsWith("sourcesjar")
            )

    /**
     * Whether a KSP task is one of the per-target ones the plugin registers for every target and
     * which now have no processor of their own.
     *
     * They are not merely idle: they still read the metadata output as a source directory, which
     * Gradle reports as an undeclared dependency between tasks. Generation happens once, so they are
     * switched off.
     *
     * A disabled task is the classic way to get a green and EMPTY build, so an exit code proves
     * nothing about this rule — [processorsOutsideCommonMetadata] is what keeps it from firing on a
     * module that does have per-target processing to do.
     */
    fun disabledAsRedundant(taskName: String): Boolean = taskName != GENERATOR && taskName.startsWith("ksp")

    /**
     * The KSP configurations a module declared a processor on **besides** the common-metadata one,
     * given every `ksp*` configuration that carries a dependency of its own.
     *
     * This is the case the wiring must not be silent about. `disabledAsRedundant` switches off every
     * per-target KSP task, which is correct exactly while there is nothing for them to do; a module
     * that processes common metadata AND, say, a `desktopTest` source set would lose the second half
     * without a word — generated code missing, tests green because they compiled against nothing.
     * The convention refuses to wire such a module rather than half-wiring it.
     *
     * KSP's own plumbing is filtered out here and not by the caller: `kspPluginClasspath` and
     * `kspPluginClasspathNonEmbeddable` carry `symbol-processing-api` in EVERY module that applies
     * the plugin, and `*ProcessorClasspath` are the resolvable configurations that extend the ones a
     * person writes. A guard that counted those would fire on every module and be deleted in a week.
     */
    fun processorsOutsideCommonMetadata(configurationsWithDependencies: Iterable<String>): List<String> =
        configurationsWithDependencies
            .filter { name ->
                name.startsWith("ksp") &&
                    name != PROCESSOR_CONFIGURATION &&
                    !name.endsWith("ProcessorClasspath") &&
                    name != "kspPluginClasspath" &&
                    name != "kspPluginClasspathNonEmbeddable"
            }.sorted()
}
