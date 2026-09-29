package probe

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl

fun main() {
    // Creating the engine is enough to link libcurl's initialisation, and with it the zlib it was
    // built against. Nothing is sent: the row is about loading, not networking.
    HttpClient(Curl).close()
    println("curl: loaded")
}
