package io.github.youndie.sborka.internal

import io.github.youndie.razves.gradle.BinarySizeExtension
import org.gradle.api.Project

/**
 * The one place in sborka that names a razves type.
 *
 * **Separate from the convention that calls it, and that is the same rule the Kotlin conventions
 * follow.** razves is `compileOnly` here, so its classes are on the compile classpath and on no
 * consumer's runtime one unless that repository applied razves itself. A class is linked when it is
 * first used, so naming `BinarySizeExtension` inside the convention script - even in a lambda that
 * only runs under `plugins.withId` - risks resolving it while applying the convention to a project
 * that has no razves. razves itself was fixed for exactly this shape: its plugin class names no
 * Kotlin Gradle Plugin type, so that the guard around the usage is a guard over the loading too.
 *
 * Called only from inside the `withId` block, so this class is loaded only where razves is.
 */
internal object SizeGate {
    fun wire(
        project: Project,
        budgetBytes: Long,
    ) {
        val binarySize = project.extensions.getByType(BinarySizeExtension::class.java)
        binarySize.budget.set(budgetBytes)
    }
}
