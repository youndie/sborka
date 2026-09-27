package stand

import kotlin.test.Test
import kotlin.test.assertEquals

/** The native half of the pair — see the class of the same name in `jvmTest`. More tests on purpose. */
class PlatformGreetingTest {
    @Test
    fun greetsOnNative() {
        assertEquals("hello, native", greeting("native"))
    }

    @Test
    fun greetsNobody() {
        assertEquals("hello, ", greeting(""))
    }

    @Test
    fun greetsTwice() {
        assertEquals("hello, hello, x", greeting(greeting("x")))
    }
}
