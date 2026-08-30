package ru.workinprogress.sborka

import org.gradle.api.provider.ListProperty

/**
 * What a repository has to add to a mutation run that its ordinary tests already get.
 *
 * A pitest minion is a JVM of its own: it is not the `Test` task, and nothing configured on that task
 * reaches it. A suite whose tests need an argument — a heap setting the code asserts about, a system
 * property naming a directory, an `-D` that unlocks a restricted header — passes under `test` and
 * fails inside a minion **without any mutation at all**, at which point the run has not found a
 * defect, it has not started. pitest reports that as a suite that fails on its own, which is the
 * right refusal for the wrong reason.
 *
 * So the arguments are said once, here:
 *
 * ```kotlin
 * plugins.withId("ru.workinprogress.sborka.mutation") {
 *     extensions.configure<MutationOptions>("sborkaMutation") {
 *         forkJvmArgs.addAll(profile)
 *         forkJvmArgs.add("-Dapp.specDir=$specDir")
 *     }
 * }
 * ```
 *
 * pitest separates them on commas, so an argument containing one cannot be passed at all — this
 * refuses such a value rather than handing pitest a command line that means something else.
 */
interface MutationOptions {
    val forkJvmArgs: ListProperty<String>
}
