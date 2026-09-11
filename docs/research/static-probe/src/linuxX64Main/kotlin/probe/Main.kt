package probe

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.addrinfo
import platform.posix.freeaddrinfo
import platform.posix.gai_strerror
import platform.posix.getaddrinfo

/*
 * What a service needs from libc, in the order it stops working.
 *
 * Each line prints `name=ok` or `name=FAIL(...)` and nothing exits early: the point of the probe is
 * the whole row, and a static-glibc binary that dies on the first name lookup would hide whether
 * the file system still worked.
 *
 * `getaddrinfo` twice, and the pair is the experiment. A static glibc resolves through NSS, which
 * it loads with `dlopen` at run time — so a statically linked binary either carries the matching
 * libnss modules or silently loses name resolution while everything else keeps working. That is the
 * failure this brief predicted for RQ2, and one lookup of `localhost` would not show it: `localhost`
 * is answered by the `files` module out of /etc/hosts, and a real service needs `dns`.
 */
@OptIn(ExperimentalForeignApi::class)
private fun resolve(host: String): String =
    memScoped {
        val hints = alloc<addrinfo>()
        hints.ai_family = AF_INET
        val result = allocPointerTo<addrinfo>()
        val rc = getaddrinfo(host, null, hints.ptr, result.ptr)
        if (rc == 0) {
            freeaddrinfo(result.value)
            "ok"
        } else {
            "FAIL(rc=$rc ${gai_strerror(rc)?.toKString()})"
        }
    }

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    println("probe=started")
    println("hosts-file-lookup=${resolve("localhost")}")
    println("dns-lookup=${resolve(args.firstOrNull() ?: "example.com")}")
    println(
        "read-file=" +
            runCatching {
                val f = platform.posix.fopen("/etc/hostname", "r") ?: error("fopen returned null")
                platform.posix.fclose(f)
                "ok"
            }.getOrElse { "FAIL(${it.message})" },
    )
    println("probe=finished")
}
