package stand

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The control that keeps the gate off test code, and it is permanent rather than run by hand.
 *
 * `:jvm-lib` is the module the stand declares hot, and the pattern below is built on every call with
 * no suppression on it — the exact shape that fails a build in main output. Here it must only be
 * printed, marked `[test]`. Take the test-output rule out of the reader and `./gradlew -p stand
 * check` goes red on this file, which is what a control is for.
 */
class CodesTest {
    private fun matchesHere(value: String): Boolean = Regex("[A-Z]{2}-[0-9]{4}").matches(value)

    @Test
    fun `a code is two letters, a dash and four digits`() {
        assertEquals(true, looksLikeCode("AB-1234"))
        assertEquals(true, matchesHere("AB-1234"))
    }

    @Test
    fun `anything else is not a code`() {
        assertEquals(false, looksLikeCode("ab-1234"))
        assertEquals(false, matchesHere("AB-12345"))
    }
}
