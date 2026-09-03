package stand

import org.gradle.api.Plugin
import org.gradle.api.Project

/** Does nothing. What is being exercised is how this module publishes, not what the plugin does. */
@Suppress(
    "kapkan:joined-at-neither-end",
    "a Gradle plugin is found through its descriptor by id, so no code in this build names the class",
)
public class NoopPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = Unit
}
