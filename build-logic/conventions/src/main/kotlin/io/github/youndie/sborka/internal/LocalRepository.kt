package io.github.youndie.sborka.internal

import java.io.File
import java.net.URI

/**
 * The directory repository `sborka.publish` registers next to `wip`, and what it does when a module
 * already declares one under the same name.
 *
 * A pre-flight before a publish and a read-back after it both need the full list of coordinates the
 * publish writes. `~/.m2` cannot give it, because it does not start empty, and a hand-written list
 * cannot either: kore 0.1.0 went out without `kore-booblik` because its read-back looped over five
 * typed coordinates out of fifteen. Three repositories had each built their own directory under a
 * different name and path, so no shared script could rely on any of them (youndie/sborka#120).
 *
 * The name and the path are kafkakn's, which already ran `publishAllPublicationsToLocalRepository`
 * against the root build's `build/local-repo`. That makes the collision the common case, not a
 * hypothetical one, and it is decided here rather than left to Gradle. Gradle does not refuse a
 * second repository called `local`: it silently renames it `local2`, so the module would get two
 * publish tasks writing one tree, or one that writes somewhere else under a name nobody typed.
 */
object LocalRepository {
    /** The repository's name, and therefore the task: `publishAllPublicationsToLocalRepository`. */
    const val NAME: String = "local"

    /** Under the ROOT build's build directory, so a multi-module publish lands in one tree. */
    const val DIRECTORY: String = "local-repo"

    sealed interface Decision {
        /** Nothing is called `local` yet: register it. */
        data object Register : Decision

        /**
         * The module already declares `local` at this very directory, the way kafkakn does. That
         * declaration IS this repository, so registering a second one would only produce `local2`.
         * The module's own is kept, and the declaration is reported as one that can be deleted.
         */
        data object Adopt : Decision

        /** The module's `local` is somewhere else, and a script reading `build/local-repo` would read nothing. */
        data class Refuse(
            val message: String,
        ) : Decision
    }

    /**
     * What to do in [projectPath], given the URL of the repository it already calls `local` (null when
     * none) and the directory this convention would register.
     *
     * Only a `file:` URL that names the same directory is the same repository. Anything else is
     * refused rather than adopted: the point of the name is that every repository means one place by
     * it, and a module whose `local` means another place would hand a shared pre-flight the wrong tree
     * with nothing failing.
     */
    fun decide(
        projectPath: String,
        declared: URI?,
        expected: File,
    ): Decision {
        if (declared == null) return Decision.Register
        if (declared.scheme == "file" && sameDirectory(File(declared), expected)) return Decision.Adopt
        return Decision.Refuse(
            "$projectPath declares a publishing repository named `$NAME` at $declared. sborka.publish " +
                "registers `$NAME` itself, at ${expected.toURI()} (the root build's build/$DIRECTORY), so " +
                "that a multi-module publish lands in one tree a pre-flight or a read-back can walk. Delete " +
                "the module's `maven { name = \"$NAME\" ... }` block to use that one, or rename it if the " +
                "directory it points at is something else.",
        )
    }

    private fun sameDirectory(
        a: File,
        b: File,
    ): Boolean =
        a.toPath().toAbsolutePath().normalize() ==
            b.toPath().toAbsolutePath().normalize()
}
