package stand

import kotlin.test.Test
import kotlin.test.assertEquals

/** Green on the debug test binary, red on the release one. See `stand/kmp-lib/build.gradle.kts`. */
class ReleaseTrapTest {
    @Test
    fun aScalarForAListFieldFallsBack() {
        assertEquals("fallback for token", encodeLeniently(listFieldEncoder, "token"))
    }
}
