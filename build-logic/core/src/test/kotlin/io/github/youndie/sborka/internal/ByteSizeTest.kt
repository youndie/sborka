package io.github.youndie.sborka.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ByteSizeTest {
    @Test
    fun theBinaryUnitsAreWhatABudgetIsWrittenIn() {
        assertEquals(52_428_800, ByteSize.parse("50MiB", "sborka.binaryBudget"))
        assertEquals(524_288, ByteSize.parse("512KiB", "sborka.binaryBudget"))
        assertEquals(1_073_741_824, ByteSize.parse("1GiB", "sborka.binaryBudget"))
        assertEquals(4096, ByteSize.parse("4096", "sborka.binaryBudget"))
        assertEquals(4096, ByteSize.parse("4096B", "sborka.binaryBudget"))
        assertEquals(52_428_800, ByteSize.parse(" 50 MiB ", "sborka.binaryBudget"))
    }

    @Test
    fun aDecimalUnitIsRefusedRatherThanGuessedAt() {
        // MB is 1000000 to a disk vendor and 1048576 to a build. A budget that means either is a
        // build failure its author will argue with.
        val failure = assertThrows<IllegalStateException> { ByteSize.parse("50MB", "sborka.binaryBudget") }

        assertTrue("KiB, MiB or GiB" in failure.message.orEmpty())
        assertTrue("sborka.binaryBudget" in failure.message.orEmpty(), "which property to fix")
    }

    @Test
    fun nonsenseIsRefusedByNameOfTheProperty() {
        for (raw in listOf("", "big", "-5MiB", "1.5MiB", "50 mib", "MiB")) {
            val failure = assertThrows<IllegalStateException> { ByteSize.parse(raw, "sborka.binaryBudget") }
            assertTrue("sborka.binaryBudget" in failure.message.orEmpty(), "for \"$raw\": ${failure.message}")
        }
    }

    @Test
    fun zeroIsRefusedBecauseItFailsEveryBuild() {
        val failure = assertThrows<IllegalArgumentException> { ByteSize.parse("0", "sborka.binaryBudget") }
        assertTrue("greater than zero" in failure.message.orEmpty())
    }
}
