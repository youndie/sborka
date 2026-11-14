package stand

import kotlin.test.Test
import kotlin.test.assertEquals

/** Red on every run, so that a later green can only have come from somewhere else. See `stand/kmp-lib/build.gradle.kts`. */
class CacheTrapTest {
    @Test
    fun failsEveryTime() {
        assertEquals("hello, cache", greeting("jvm"))
    }
}
