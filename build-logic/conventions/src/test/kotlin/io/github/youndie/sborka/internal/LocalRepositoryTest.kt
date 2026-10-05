package io.github.youndie.sborka.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI

/**
 * What `sborka.publish` does with a module that already declares a repository called `local`.
 *
 * Worth a test because Gradle does not fail on the collision: a second repository of one name is
 * renamed `local2` without a word. A rule that adopts too much hands a shared pre-flight the wrong
 * tree, and a rule that adopts too little gives every module two publish tasks writing one directory.
 */
class LocalRepositoryTest {
    private val expected = File("/work/kafkakn/build/local-repo")

    @Test
    fun `a module with no local gets one registered`() {
        assertEquals(LocalRepository.Decision.Register, LocalRepository.decide(":core", null, expected))
    }

    @Test
    fun `kafkakn's own declaration is the same repository and is kept`() {
        // What `uri(rootProject.layout.buildDirectory.dir("local-repo"))` gives: a trailing slash the
        // File form does not have. A comparison of strings would refuse the module that motivated this.
        val kafkakn = URI("file:/work/kafkakn/build/local-repo/")
        assertEquals(LocalRepository.Decision.Adopt, LocalRepository.decide(":kafkakn-core", kafkakn, expected))
        val dotted = URI("file:/work/kafkakn/build/../build/local-repo")
        assertEquals(LocalRepository.Decision.Adopt, LocalRepository.decide(":kafkakn-core", dotted, expected))
    }

    @Test
    fun `a local somewhere else is refused, naming both places and what to delete`() {
        val elsewhere = URI("file:/work/kafkakn/kafkakn-core/build/repo")
        val decision = LocalRepository.decide(":kafkakn-core", elsewhere, expected)
        assertTrue(decision is LocalRepository.Decision.Refuse, "$decision")
        val message = (decision as LocalRepository.Decision.Refuse).message
        listOf(":kafkakn-core", elsewhere.toString(), expected.toURI().toString(), "maven { name = \"local\"").forEach {
            assertTrue(it in message, "the refusal does not mention $it: $message")
        }
    }

    @Test
    fun `a local that is not a directory is refused`() {
        val remote = URI("https://reposilite.kotlin.website/snapshots")
        assertTrue(LocalRepository.decide(":core", remote, expected) is LocalRepository.Decision.Refuse)
        val notMaven = URI.create("urn:not-a-maven-repository")
        assertTrue(LocalRepository.decide(":core", notMaven, expected) is LocalRepository.Decision.Refuse)
    }
}
