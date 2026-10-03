package io.github.youndie.sborka.internal

import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinSingleTargetExtension
import java.io.File

// Which source directories feed a JVM `Test` task, read off the Kotlin compilation that produced its
// classes.
//
// A CLASS OF ITS OWN, AWAY FROM `DeclaredTests`, because this one names Kotlin types and that one is
// loaded in every module with a `Test` task, Kotlin or not. The caller reaches this only where a
// `kotlin` extension exists, so the class is loaded only where the Kotlin plugin is.
object TestSources {
    // MATCHED BY OUTPUT, NOT BY NAME. The compilation whose classes directories are this task's test
    // classes directories is the one that feeds it — which covers `test` under kotlin("jvm"),
    // `jvmTest` under a multiplatform `jvm()`, a second JVM target, and an Android unit test, without
    // a table of task names that the next target shape would fall outside of.
    //
    // `allKotlinSourceSets` is the compilation's own source set and everything it depends on — for
    // `jvmTest` that is `jvmTest` and `commonTest`, and never `nativeTest`, which is the whole point.
    //
    // Empty when nothing matches, and the caller says what it does instead.
    fun of(
        project: Project,
        testClassesDirs: Set<File>,
    ): List<File> {
        val targets =
            when (val kotlin = project.extensions.findByName("kotlin")) {
                is KotlinMultiplatformExtension -> kotlin.targets.toList()
                is KotlinSingleTargetExtension<*> -> listOf(kotlin.target)
                else -> return emptyList()
            }

        return targets
            .flatMap { it.compilations }
            .filter { compilation ->
                compilation.output.classesDirs.files
                    .any { it in testClassesDirs }
            }.flatMap { compilation -> compilation.allKotlinSourceSets.flatMap { it.kotlin.srcDirs } }
            .distinct()
    }
}
