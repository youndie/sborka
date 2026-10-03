package io.github.youndie.sborka.internal

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * The one slot every Kotlin/Wasm and Kotlin/JS link of a build queues for (sborka#132).
 *
 * It holds nothing and does nothing: what makes it work is `maxParallelUsages = 1` on its
 * registration in `sborka.base` and `usesService` on each link task, which together let Gradle hand
 * the slot to one link at a time. The class exists because a registration needs a type.
 */
abstract class WebLinkSlots : BuildService<BuildServiceParameters.None> {
    companion object {
        /** The registration name; one per build, whichever module asks first. */
        const val NAME: String = "sborkaWebLinkSlots"

        /** The Gradle property that switches the convention off. On by default. */
        const val PROPERTY: String = "sborka.serializeWebLinks"
    }
}
