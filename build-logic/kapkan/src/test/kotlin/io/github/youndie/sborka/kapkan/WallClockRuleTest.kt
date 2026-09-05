package io.github.youndie.sborka.kapkan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WallClockRuleTest {
    @Test
    fun `System currentTimeMillis is the wall clock`() {
        assertEquals(listOf("kapkan:wall-clock"), lint("fun t(): Long = System.currentTimeMillis()\n").ids())
    }

    @Test
    fun `Clock System now is the wall clock`() {
        // The pair that would go wrong quietly: `Clock.System` ENDS WITH `.System`, so a rule that
        // matched the shorter receiver first would read this as `System.now()` — a function that does
        // not exist — and pass the commonest form of the defect.
        assertEquals(listOf("kapkan:wall-clock"), lint("fun t() = Clock.System.now()\n").ids())
    }

    @Test
    fun `a fully qualified receiver is the same clock`() {
        assertEquals(listOf("kapkan:wall-clock"), lint("fun t() = kotlinx.datetime.Clock.System.now()\n").ids())
    }

    @Test
    fun `java time now is the wall clock`() {
        assertEquals(listOf("kapkan:wall-clock"), lint("fun t() = LocalDateTime.now()\n").ids())
    }

    @Test
    fun `System nanoTime is not`() {
        // A monotonic clock cannot be subtracted from anybody's epoch, so it cannot produce B-29's
        // defect. Every use of it in the portfolio measures a duration, which is what it is for.
        assertEquals(emptyList<String>(), lint("fun t(): Long = System.nanoTime()\n").ids())
    }

    @Test
    fun `a now handed in is the fix, not the defect`() {
        assertEquals(emptyList<String>(), lint("class V(val now: () -> Long) { fun t() = now() }\n").ids())
    }

    @Test
    fun `somebody else's now is not this rule's business`() {
        assertEquals(emptyList<String>(), lint("fun t() = repository.now()\n").ids())
    }

    @Test
    fun `a suppression with a reason silences it`() {
        val code =
            """
            @Suppress("ktlint:kapkan:wall-clock", "this is the Clock port; everything else reads it")
            fun t(): Long = System.currentTimeMillis()
            """.trimIndent()
        assertEquals(emptyList<String>(), lint(code).ids())
    }

    @Test
    fun `a suppression without a reason silences the clock and fails on its own`() {
        // The suppression works — ktlint honours it — and the guard that asks why does not, because
        // it is a different rule with a different id.
        val code =
            """
            @Suppress("ktlint:kapkan:wall-clock")
            fun t(): Long = System.currentTimeMillis()
            """.trimIndent()
        assertEquals(listOf("kapkan:suppression-needs-a-reason"), lint(code).ids())
    }
}
