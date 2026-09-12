package io.github.youndie.sborka.probe

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * The probe probed, on every target this module declares.
 *
 * `runBlocking` and not `runTest`: the test dispatcher's clock is virtual, so anything with a timeout
 * around a socket reports a timeout before the socket has done anything — a harness verdict that
 * reads exactly like a platform one. That mistake was made once while this module was being written.
 *
 * `localhost` rather than a public name, so the suite is hermetic. It still goes through name
 * resolution, answered from the hosts file rather than from DNS — which is weaker than what a
 * consumer should point [probePlatform] at, and the KDoc there says so.
 */
class PlatformProbeTest {
    // A BLOCK BODY, not `= runBlocking { … }`. The expression form returns the block's value, and
    // Kotlin/Native refuses a @Test that returns anything — "Test function must return Unit", which
    // is the same class of defect sborka's own `DeclaredTests` check exists for, caught here by the
    // compiler that is stricter about it.
    @Test
    fun everyQuestionIsAnsweredOnThisTarget() {
        runBlocking {
            SelectorManager(Dispatchers.Default).use { selector ->
                aSocket(selector).tcp().bind(InetSocketAddress("127.0.0.1", 0)).use { server ->
                    val report = probePlatform("localhost", (server.localAddress as InetSocketAddress).port)
                    // Printed rather than only asserted: the whole point of the report is that
                    // somebody reads what it covered, and a green test prints nothing by default.
                    println(report)
                    assertTrue(report.failures.isEmpty(), "$report")
                    report.orFail()
                }
            }
        }
    }

    @Test
    fun aRefusedConnectionIsReportedRatherThanThrown() {
        runBlocking {
            // Port 1 with nothing on it: the probe has to come back with a finding, not an exception,
            // or a repository's whole suite dies on the first thing the platform cannot do.
            val finding = resolvesHostname("localhost", 1)
            assertFalse(finding.ok)
            assertTrue(finding.verdict.isNotEmpty(), "a failure with no verdict says nothing")
        }
    }

    @Test
    fun theReportNamesWhatItDidNotCover() {
        val report = PlatformReport(platformTarget, listOf(readsEnvironment("PATH")))
        assertTrue(report.notCovered.isNotEmpty())
        assertTrue("not covered" in report.toString())
    }
}
