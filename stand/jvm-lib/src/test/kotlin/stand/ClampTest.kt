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
