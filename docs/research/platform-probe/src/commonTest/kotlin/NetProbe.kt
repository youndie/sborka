import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

// Does ktor-network resolve a HOSTNAME on this target? metrik's research says Kotlin/Native does not
// and fails with EINVAL, and that a whole fleet of agents was silent from its first day because of
// it. The probe uses ktor's own InetSocketAddress rather than getaddrinfo on purpose: the failure
// was in that API, and an assertion through a different one would pass while the service did not.
class NetProbe {
    @Test
    fun resolveAndConnect() =
        runBlocking {
            // NOT `runTest`: its clock is virtual, so `withTimeout` fires before the socket has done
            // anything and every host comes back "timed out". The first run of this probe said
            // exactly that on both targets, which reads as a platform verdict and is a harness one.
            for (host in listOf("127.0.0.1", "localhost", "example.com")) {
                val verdict =
                    runCatching {
                        withTimeout(8000) {
                            SelectorManager(Dispatchers.Default).use { selector ->
                                aSocket(selector).tcp().connect(InetSocketAddress(host, 80)).use { "connected" }
                            }
                        }
                    }.getOrElse { "${it::class.simpleName}: ${it.message}" }
                println("NETPROBE\t$host\t$verdict")
            }
        }
}
