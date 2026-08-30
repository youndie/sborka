package stand

import kotlin.test.Test
import kotlin.test.assertEquals

// `kotlin.test`, which resolves to `kotlin-test-junit5` and carries a Jupiter of its own. The version
// that actually runs is the one `sborka.junitVersion` names, because `sborka.test` puts the JUnit BOM
// on this classpath as an ENFORCED platform — a plain one loses to the transitive dependency.
class ClampTest {
    @Test
    fun `below the floor comes back as the floor`() {
        assertEquals(0, clamp(-5, 0, 10))
    }

    @Test
    fun `above the ceiling comes back as the ceiling`() {
        assertEquals(10, clamp(50, 0, 10))
    }

    @Test
    fun `inside the range is left alone`() {
        assertEquals(4, clamp(4, 0, 10))
    }
}

/**
 * A test that cannot pass without an argument the build delivered, which is what makes it a control
 * for `sborkaMutation.forkJvmArgs`.
 *
 * `test` and `mutationTest` deliver it through different mechanisms — a system property on the `Test`
 * task, and pitest's `--jvmArgs` for the minions it forks. The second one is easy to leave out and
 * impossible to notice: everything about the mutation run looks the same, and pitest reports a suite
 * that fails on its own. So the stand asks for it in both, and a convention that stops passing it
 * fails `:jvm-lib:mutationTest` in this repository rather than in a repository being migrated.
 */
class ForkedArgumentTest {
    @Test
    fun `the argument the build promised is here`() {
        assertEquals(
            "delivered",
            System.getProperty("stand.forkArgument"),
            "stand.forkArgument is not set: whatever forked this JVM did not carry the build's arguments",
        )
    }
}
