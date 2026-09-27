package stand

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ONE OF TWO CLASSES BY THIS NAME, and the other is in `nativeTest` with a different number of tests.
 *
 * Legal, because no compilation sees both, and the shape that turned a consuming repository red on
 * some runners and not others: the declared-tests check read the whole `src/`, filed both files under
 * one name and demanded the native file's count from `jvmTest`. Here so that `:kmp-lib:jvmTest` is the
 * check reading the sources of its own compilation, under the configuration cache, on every run.
 */
class PlatformGreetingTest {
    @Test
    fun greetsOnTheJvm() {
        assertEquals("hello, jvm", greeting("jvm"))
    }
}
