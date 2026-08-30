package stand

import org.gradle.api.Plugin
import org.gradle.api.Project

/** Does nothing. What is being exercised is how this module publishes, not what the plugin does. */
public class NoopPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = Unit
}
