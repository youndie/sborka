package check

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Can this image's loader load this binary? Answered from files alone, in the order the loader asks:
 * the interpreter, then every NEEDED entry transitively, then every symbol version.
 *
 * WHAT IT CANNOT SEE, and says so on every run: anything opened with `dlopen` (gconv modules, NSS
 * modules on an old glibc), and files that are not libraries at all (CA certificates, time zones).
 * No ELF entry names them.
 */
class LoadCheck(private val fs: ImageFs, private val env: Map<String, String>) {
    sealed interface Problem { val line: String }
    data class MissingInterpreter(val path: String) : Problem {
        override val line = "missing-interpreter $path — the kernel cannot start the binary; Docker reports the BINARY as not found"
    }
    data class MissingLibrary(val name: String, val neededBy: String) : Problem {
        override val line = "missing-library $name needed by $neededBy"
    }
    data class MissingVersion(val version: String, val from: String, val neededBy: String, val defined: Set<String>) : Problem {
        override val line = "missing-version $version from $from needed by $neededBy — it defines up to ${highestGlibc(defined) ?: defined.lastOrNull() ?: "nothing"}"
    }

    class Result(val problems: List<Problem>, val resolved: List<String>, val warnings: List<String>)

    /**
     * Where a library is looked for when no RUNPATH, LD_LIBRARY_PATH or ld.so.cache names it: the
     * directories Debian's and Ubuntu's x86_64 loader searches by itself. B-27's r7a is the evidence
     * for the first: a scratch image with no ld.so.cache loaded its libraries from there.
     */
    private val defaultDirs = listOf("lib/x86_64-linux-gnu", "usr/lib/x86_64-linux-gnu", "lib", "usr/lib")

    fun run(binaryPath: String): Result {
        val problems = mutableListOf<Problem>()
        val resolved = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val (binPath, binFile) = fs.resolveFile(binaryPath) ?: error("$binaryPath is not a file in the image")
        val binary = Elf.parse(binFile.bytes)
        if (!binary.dynamic) return Result(emptyList(), listOf("$binPath: static, nothing for a loader to do"), warnings)

        binary.interpreter?.let { interp ->
            val hit = fs.resolveFile(interp)
            if (hit == null) problems += MissingInterpreter(interp) else resolved += "interpreter $interp -> /${hit.first}"
        }

        val cache = LdSoCache.read(fs)
        warnings += if (cache == null) "no /etc/ld.so.cache: default directories only" else "ld.so.cache: ${cache.size} entries read"

        // Loaded objects by the NAME they were asked for, as the loader matches NEEDED against
        // what it already has.
        val loaded = linkedMapOf<String, Pair<String, Elf>>()
        val queue = ArrayDeque(listOf(Triple("/$binPath", binary, binPath.substringBeforeLast('/', ""))))
        val all = mutableListOf("/$binPath" to binary)
        while (queue.isNotEmpty()) {
            val (who, elf, origin) = queue.removeFirst()
            for (name in elf.needed) {
                if (name in loaded) continue
                val candidates = searchPath(name, elf, origin, cache)
                val hit = candidates.firstNotNullOfOrNull { fs.resolveFile(it) }
                if (hit == null) {
                    problems += MissingLibrary(name, who)
                    continue
                }
                val lib = try {
                    Elf.parse(hit.second.bytes)
                } catch (e: Elf.NotElf) {
                    problems += MissingLibrary("$name (found /${hit.first}, but ${e.message})", who)
                    continue
                }
                loaded[name] = "/${hit.first}" to lib
                all += "/${hit.first}" to lib
                resolved += "$name -> /${hit.first} (needed by $who)"
                queue += Triple("/${hit.first}", lib, hit.first.substringBeforeLast('/', ""))
            }
        }

        var versionsChecked = 0
        for ((who, elf) in all) {
            for ((file, needs) in elf.versionNeeds) {
                val provider = loaded[file]?.second ?: continue // already a missing library
                for (need in needs) {
                    versionsChecked++
                    if (need.name in provider.versionsDefined) continue
                    if (need.weak) {
                        warnings += "weak version ${need.name} from $file needed by $who is not defined (the loader allows it)"
                    } else {
                        problems += MissingVersion(need.name, file, who, provider.versionsDefined)
                    }
                }
            }
        }
        resolved += "$versionsChecked symbol-version requirements checked across ${all.size} objects"
        return Result(problems, resolved, warnings)
    }

    private fun searchPath(name: String, requester: Elf, origin: String, cache: Map<String, String>?): List<String> {
        if ('/' in name) return listOf(name)
        val dirs = mutableListOf<String>()
        dirs += requester.runpath.map { it.replace("\$ORIGIN", "/$origin").replace("\${ORIGIN}", "/$origin") }
        env["LD_LIBRARY_PATH"]?.split(':')?.filter(String::isNotEmpty)?.let(dirs::addAll)
        val fromCache = cache?.get(name)
        return dirs.map { "$it/$name" } + listOfNotNull(fromCache) + defaultDirs.map { "$it/$name" }
    }

    companion object {
        fun highestGlibc(defined: Set<String>): String? =
            defined.filter { it.startsWith("GLIBC_2.") }.maxByOrNull { v ->
                v.removePrefix("GLIBC_").split('.').map { it.toIntOrNull() ?: 0 }.let { it[0] * 1000 + it.getOrElse(1) { 0 } }
            }
    }
}

/** `/etc/ld.so.cache`, the `glibc-ld.so.cache1.1` format: soname → path. */
object LdSoCache {
    private const val MAGIC = "glibc-ld.so.cache1.1"

    fun read(fs: ImageFs): Map<String, String>? {
        val bytes = fs.resolveFile("etc/ld.so.cache")?.second?.bytes ?: return null
        val start = String(bytes, Charsets.ISO_8859_1).indexOf(MAGIC)
        require(start >= 0) { "/etc/ld.so.cache is in a format this prototype does not read" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = b.getInt(start + 20)
        val out = linkedMapOf<String, String>()
        for (i in 0 until n) {
            val e = start + 48 + i * 24
            val key = cString(bytes, start + b.getInt(e + 4))
            val value = cString(bytes, start + b.getInt(e + 8))
            out.putIfAbsent(key, value)
        }
        return out
    }

    private fun cString(bytes: ByteArray, at: Int): String {
        var end = at
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        return String(bytes, at, end - at, Charsets.UTF_8)
    }
}
