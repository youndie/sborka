package stand

import kotlin.test.Test
import kotlin.test.assertEquals

// The function this covers exists to be a perf-lint finding, and it is tested anyway: an untested
// function in the stand drops `mutationTest`'s numbers, and a stand whose own measurements drift
// because of a fixture is a stand that stops being read.
class DigitsTest {
    @Test
    fun `the positives come back scaled and largest first`() {
        assertEquals("30,20,10", heaviest(listOf(1, 2, 3), 10))
    }

    @Test
    fun `everything at or below zero is dropped`() {
        assertEquals("2", heaviest(listOf(-3, 0, 1), 2))
    }

    @Test
    fun `nothing left is an empty line rather than a missing one`() {
        assertEquals("", heaviest(listOf(-1), 5))
    }
}
