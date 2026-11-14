package io.github.youndie.sborka.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The reader behind "a test task whose last run failed is not up to date". The stand proves the
 * convention end to end (`.github/workflows/check.yaml`, the cache trap); this proves the reader on
 * the shapes a stand does not produce on purpose.
 */
class TestReplayTest {
    @Test
    fun `failures and errors are both counted, however deep the results are nested`(
        @TempDir tmp: File,
    ) {
        val results = File(tmp, "test-results/jvmTest").apply { mkdirs() }
        File(results, "TEST-pkg.FirstTest.xml").writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
            |<testsuite name="pkg.FirstTest" tests="3" skipped="0" failures="1" errors="0" timestamp="t">
            |<testcase name="a"><failure message="boom">trace</failure></testcase>
            |</testsuite>
            """.trimMargin(),
        )
        val nested = File(results, "binaries").apply { mkdirs() }
        File(nested, "TEST-pkg.SecondTest.xml").writeText(
            """<testsuite name="pkg.SecondTest" tests="2" skipped="0" failures="0" errors="2" timestamp="t">""",
        )

        assertEquals(3, TestReplay.failedIn(File(tmp, "test-results")))
    }

    @Test
    fun `a passing suite is zero`(
        @TempDir tmp: File,
    ) {
        File(tmp, "TEST-pkg.GreenTest.xml").writeText(
            """<testsuite name="pkg.GreenTest" tests="4" skipped="1" failures="0" errors="0" timestamp="t">""",
        )

        assertEquals(0, TestReplay.failedIn(tmp))
    }

    @Test
    fun `text inside a failure message is not read as a count`(
        @TempDir tmp: File,
    ) {
        // The header is the only place a count lives. A reader that searched the whole file would
        // take this assertion message for three more failures.
        File(tmp, "TEST-pkg.QuotedTest.xml").writeText(
            """<testsuite name="pkg.QuotedTest" tests="1" skipped="0" failures="0" errors="0" timestamp="t">
            |<testcase name="a"><system-out>expected failures="3"</system-out></testcase>
            |</testsuite>
            """.trimMargin(),
        )

        assertEquals(0, TestReplay.failedIn(tmp))
    }

    @Test
    fun `a missing results directory is zero`(
        @TempDir tmp: File,
    ) {
        assertEquals(0, TestReplay.failedIn(File(tmp, "never-written")))
    }
}
