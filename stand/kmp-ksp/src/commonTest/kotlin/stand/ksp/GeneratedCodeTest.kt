package stand.ksp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The generated file, read from `commonTest` — which is the only thing that can tell a wired module
 * from an unwired one.
 *
 * A build that never ran the processor fails this at COMPILATION, with `standRegistry` unresolved,
 * and that is the point: the source directory the convention adds is the half no task graph shows.
 * The contents are asserted too, so a processor that ran and found nothing cannot pass as a
 * processor that ran.
 */
class GeneratedCodeTest {
    @Test
    fun `the processor ran over common metadata and every target compiled what it wrote`() {
        assertEquals(listOf("Alpha", "Beta"), standRegistry)
    }
}
