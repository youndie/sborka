package stand.ksp

import kotlin.test.Test
import kotlin.test.assertEquals

@Target(AnnotationTarget.CLASS)
annotation class Marked

@Marked
class Gamma

/**
 * The per-target processor ran and its output compiled — asserted from the module `sborka.kmp` is
 * supposed to keep its hands off.
 *
 * `standRegistry` here is generated into `jvmTest`, not into common metadata. The convention
 * switches off every per-target KSP task in the modules it wires; if its gate ever widens to this
 * one, this file stops compiling, which is a louder answer than a task list would give.
 */
class PlatformGeneratedCodeTest {
    @Test
    fun `the per-target processor was left alone`() {
        assertEquals(listOf("Gamma"), standRegistry)
    }
}
