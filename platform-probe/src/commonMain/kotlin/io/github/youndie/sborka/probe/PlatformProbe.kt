package io.github.youndie.sborka.probe

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString

/** One question the platform was asked, and what it answered. */
public class PlatformFinding internal constructor(
    public val name: String,
    public val ok: Boolean,
    public val verdict: String,
) {
    override fun toString(): String = "$name: ${if (ok) "ok" else verdict}"
}

/**
 * What the platform answered, and — in the same object — what it was never asked.
 *
 * The second half is not politeness. Every divergence this portfolio has paid for was in the
 * platform layer, and a green report that does not say where it stopped looking accumulates trust it
 * has not earned. [notCovered] is printed beside the result for that reason.
 */
public class PlatformReport internal constructor(
    public val target: String,
    public val findings: List<PlatformFinding>,
) {
    public val failures: List<PlatformFinding> get() = findings.filterNot { it.ok }

    /**
     * What was NOT asked, computed from what was.
     *
     * A fixed list would have kept claiming TLS was uncovered on a run that covered it, which is the
     * same defect as claiming coverage that did not happen — a report that is wrong in the reassuring
     * direction rather than the alarming one.
     */
    public val notCovered: List<String> =
        listOfNotNull(
            "TLS through the client engine this repository pins"
                .takeIf { findings.none { finding -> finding.name.startsWith("reach-over-tls") } },
            "the Ktor plugins this repository pins, which fail at compile time on a target that lacks them",
            "standard-library behaviour, which moves at a version bump rather than at a commit",
        )

    override fun toString(): String =
        buildString {
            append("platform probe on ")
            append(target)
            append(": ")
            append(findings.count { it.ok })
            append('/')
            append(findings.size)
            appendLine(" ok")
            findings.forEach { appendLine("  $it") }
            appendLine("  not covered:")
            notCovered.forEach { appendLine("    $it") }
        }
}

/** Throws with the whole report if anything failed; returns it unchanged otherwise. */
public fun PlatformReport.orFail(): PlatformReport {
    check(failures.isEmpty()) { "the platform does not do what this code assumes.\n$this" }
    return this
}

private inline fun finding(
    name: String,
    body: () -> String,
): PlatformFinding =
    runCatching { PlatformFinding(name, ok = true, verdict = body()) }
        .getOrElse { PlatformFinding(name, ok = false, verdict = "${it::class.simpleName}: ${it.message}") }

/**
 * Resolves [host] and opens a TCP connection to it.
 *
 * **A hostname, and a real connection.** `127.0.0.1` needs no resolver and a mock needs no socket;
 * the failure this exists for was a name that would not resolve on Kotlin/Native while every syscall
 * underneath worked, and it survived for months because the test substituted a fake sender. Through
 * ktor's own `InetSocketAddress` for the same reason: that is the API the service uses.
 */
public suspend fun resolvesHostname(
    host: String,
    port: Int,
): PlatformFinding =
    finding("resolve-and-connect($host:$port)") {
        SelectorManager(Dispatchers.Default).use { selector ->
            aSocket(selector).tcp().connect(InetSocketAddress(host, port)).use { "connected" }
        }
    }

/**
 * Reads [name] from the environment.
 *
 * Absent is not a failure — the question is whether the platform can be asked at all, and a variable
 * that is not set answers that as well as one that is.
 */
public fun readsEnvironment(name: String): PlatformFinding =
    finding("read-environment($name)") { environmentVariable(name)?.let { "set" } ?: "not set" }

/** Writes a file, reads it back, and deletes it, through the file system the service uses. */
public fun roundTripsAFile(): PlatformFinding =
    finding("round-trip-a-file") {
        val path = Path(SystemTemporaryDirectory, "sborka-platform-probe.txt")
        try {
            SystemFileSystem.sink(path).buffered().use { it.writeString(PROBE_CONTENT) }
            val read = SystemFileSystem.source(path).buffered().use { it.readString() }
            check(read == PROBE_CONTENT) { "wrote ${PROBE_CONTENT.length} bytes and read back $read" }
            "ok"
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
        }
    }

/**
 * Dispatches onto [Dispatchers.IO].
 *
 * The cheapest member of this family and the one with the best story: `Dispatchers.IO` on
 * Kotlin/Native is an extension property, so without `import kotlinx.coroutines.IO` the compiler
 * resolves the internal member of the same name and says "it is internal" — which was recorded in
 * this portfolio as a platform limitation, and justified a thread, for as long as nobody compiled
 * the line.
 */
public suspend fun hasIoDispatcher(): PlatformFinding =
    finding("dispatch-on-io") {
        val io: CoroutineDispatcher = Dispatchers.IO
        withContext(io) { io.toString() }
    }

/**
 * Makes one request through a caller-supplied [request] and reports what happened.
 *
 * **The engine is never this module's.** It is the repository's — `curl` on native and `cio` on the
 * JVM in at least one service here, because `ktor-client-cio` has no TLS on Kotlin/Native and that
 * cost this portfolio months of notifications that silently never left the process. A probe that
 * brought its own engine would answer for an engine nobody ships.
 *
 * So [request] is a lambda and not an `HttpClient`: this module gains no client dependency, the
 * consumer keeps its own, and what is asserted is the path that actually runs in production.
 *
 * ```kotlin
 * reachesOverTls("https://example.com") { url -> client.get(url) }
 * ```
 */
public suspend fun reachesOverTls(
    url: String,
    request: suspend (String) -> Unit,
): PlatformFinding =
    finding("reach-over-tls($url)") {
        request(url)
        "ok"
    }

/**
 * Asks the platform everything this module knows how to ask.
 *
 * [host] and [port] should be the repository's own stand rather than a public address: a probe that
 * needs the internet is a probe that goes red on an outage somebody else had, and gets switched off.
 */
public suspend fun probePlatform(
    host: String,
    port: Int,
    environmentVariable: String = "PATH",
    tlsUrl: String? = null,
    tlsRequest: (suspend (String) -> Unit)? = null,
): PlatformReport =
    PlatformReport(
        target = platformTarget,
        findings =
            listOfNotNull(
                resolvesHostname(host, port),
                readsEnvironment(environmentVariable),
                roundTripsAFile(),
                hasIoDispatcher(),
                if (tlsUrl != null && tlsRequest != null) reachesOverTls(tlsUrl, tlsRequest) else null,
            ),
    )

/** Where a build told the probe to look, or `null` if it did not. */
public class ProbeTarget internal constructor(
    public val host: String,
    public val port: Int,
)

/**
 * The target `sborka.parity` puts in the environment of every test task, or `null` when no build did.
 *
 * `null` is not a licence to skip. A repository with no stand of its own should probe a listener the
 * test binds itself — weaker, because the name is answered from the hosts file rather than by DNS,
 * and honest, because the report says which of the two it was. A probe that quietly does nothing
 * when unconfigured is the shape that makes a lookup test vacuous.
 */
public fun configuredProbeTarget(): ProbeTarget? {
    val host = environmentVariable(HOST_VARIABLE) ?: return null
    val port = environmentVariable(PORT_VARIABLE)?.toIntOrNull() ?: return null
    return ProbeTarget(host, port)
}

internal const val HOST_VARIABLE: String = "SBORKA_PARITY_HOST"

internal const val PORT_VARIABLE: String = "SBORKA_PARITY_PORT"

private const val PROBE_CONTENT = "sborka platform probe"

internal expect fun environmentVariable(name: String): String?

/** The target this was compiled for, named the way the build names it. */
public expect val platformTarget: String
