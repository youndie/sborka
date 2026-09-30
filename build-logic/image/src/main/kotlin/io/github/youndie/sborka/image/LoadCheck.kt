package io.github.youndie.sborka.image

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Can this image's loader load this binary? Answered from files alone, in the order the loader asks:
 * the interpreter, then every NEEDED entry transitively, then every symbol version.
 *
 * WHAT IT CANNOT SEE, and [NOT_CHECKED] says so on every run: anything opened with `dlopen` (gconv
 * modules, NSS modules on an old glibc), and files that are not libraries at all (CA certificates,
 * time zones). No ELF entry names them, and the corpus keeps a row (r7b) where the check says "loads"
 * and the container fails, so that a green is never read as more than it is.
 */
public class LoadCheck(
    private val fs: ImageFs,
    private val environment: Map<String, String>,
) {
    public sealed interface Problem {
        public val line: String
    }

    public data class MissingInterpreter(
        val path: String,
    ) : Problem {
        override val line: String =
            "missing-interpreter $path — the kernel cannot start the binary; Docker reports the BINARY as not found"
    }

    public data class MissingLibrary(
        val name: String,
        val neededBy: String,
    ) : Problem {
        override val line: String = "missing-library $name needed by $neededBy"
    }

    public data class MissingVersion(
        val version: String,
        val from: String,
        val neededBy: String,
        val defined: Set<String>,
    ) : Problem {
        override val line: String =
            "missing-version $version from $from needed by $neededBy — it defines up to " +
                (highestGlibc(defined) ?: defined.lastOrNull() ?: "nothing")
    }

    public class Result(
        public val problems: List<Problem>,
        public val resolved: List<String>,
        public val notes: List<String>,
    ) {
        public val loads: Boolean get() = problems.isEmpty()

        /** One line, the problems joined, or `loads`. */
        public val verdict: String
            get() = if (loads) "loads" else problems.joinToString("; ") { it.line.substringBefore(" —") }
    }

    /**
     * Where a library is looked for when no RUNPATH, LD_LIBRARY_PATH or ld.so.cache names it: the
     * directories Debian's and Ubuntu's x86_64 loader searches by itself. The corpus's r7a is the
     * evidence for the first: a scratch image with no ld.so.cache loaded its libraries from there.
     */
    private val defaultDirs = listOf("lib/x86_64-linux-gnu", "usr/lib/x86_64-linux-gnu", "lib", "usr/lib")

    public fun run(binaryPath: String): Result {
        val problems = mutableListOf<Problem>()
        val resolved = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val (binPath, binFile) = fs.resolveFile(binaryPath) ?: error("$binaryPath is not a file in the image")
        val binary = Elf.parse(binFile.bytes)
        if (!binary.dynamic) return Result(emptyList(), listOf("/$binPath: static, nothing for a loader to do"), notes)

        // NO LOADER, NOTHING ELSE HAPPENS. The kernel hands the binary to its interpreter; without
        // one there is no library search to report on. The first scoring run listed every NEEDED
        // entry as missing on `scratch` as well — true of the files, and nothing the loader would
        // ever have got to.
        binary.interpreter?.let { interp ->
            val hit = fs.resolveFile(interp)
            if (hit == null) {
                return Result(
                    listOf(MissingInterpreter(interp)),
                    resolved,
                    listOf("libraries not looked up: there is no loader to look them up"),
                )
            }
            resolved += "interpreter $interp -> /${hit.first}"
        }

        val cache = LdSoCache.read(fs)
        notes += if (cache == null) NO_CACHE else "ld.so.cache: ${cache.size} entries read"

        // Loaded objects by the NAME they were asked for, as the loader matches NEEDED against what
        // it already has.
        val loaded = linkedMapOf<String, Elf>()
        val queue = ArrayDeque(listOf(Loaded("/$binPath", binary)))
        val all = mutableListOf(Loaded("/$binPath", binary))
        while (queue.isNotEmpty()) {
            val who = queue.removeFirst()
            for (name in who.elf.needed) {
                if (name in loaded) continue
                val hit = searchPath(name, who, cache).firstNotNullOfOrNull { fs.resolveFile(it) }
                if (hit == null) {
                    problems += MissingLibrary(name, who.path)
                    continue
                }
                val lib =
                    try {
                        Elf.parse(hit.second.bytes)
                    } catch (e: Elf.NotElf) {
                        problems += MissingLibrary("$name (found /${hit.first}, but ${e.message})", who.path)
                        continue
                    }
                loaded[name] = lib
                val next = Loaded("/${hit.first}", lib)
                all += next
                queue += next
                resolved += "$name -> /${hit.first} (needed by ${who.path})"
            }
        }

        var versionsChecked = 0
        for (who in all) {
            for ((file, needs) in who.elf.versionNeeds) {
                // a provider that did not load is already a missing library
                val provider = loaded[file] ?: continue
                for (need in needs) {
                    versionsChecked++
                    if (need.name in provider.versionsDefined) continue
                    if (need.weak) {
                        notes +=
                            "weak version ${need.name} from $file needed by ${who.path} is not defined (the loader allows it)"
                    } else {
                        problems += MissingVersion(need.name, file, who.path, provider.versionsDefined)
                    }
                }
            }
        }
        resolved += "$versionsChecked symbol-version requirements checked across ${all.size} objects"
        return Result(problems, resolved, notes)
    }

    private fun searchPath(
        name: String,
        requester: Loaded,
        cache: Map<String, String>?,
    ): List<String> {
        if ('/' in name) return listOf(name)
        val origin = requester.path.substringBeforeLast('/', "")
        val dirs = mutableListOf<String>()
        dirs += requester.elf.runpath.map { it.replace("\$ORIGIN", origin).replace("\${ORIGIN}", origin) }
        environment["LD_LIBRARY_PATH"]?.split(':')?.filter(String::isNotEmpty)?.let(dirs::addAll)
        return dirs.map { "$it/$name" } + listOfNotNull(cache?.get(name)) + defaultDirs.map { "$it/$name" }
    }

    private class Loaded(
        val path: String,
        val elf: Elf,
    )

    public companion object {
        private const val NO_CACHE = "no /etc/ld.so.cache: default directories only"

        /** Printed after every verdict, green or not. */
        public const val NOT_CHECKED: String =
            "not checked: dlopen targets (gconv, NSS), CA certificates, time zones — no ELF entry names them"

        internal fun highestGlibc(defined: Set<String>): String? =
            defined.filter { it.startsWith("GLIBC_2.") }.maxByOrNull { v ->
                v.removePrefix("GLIBC_").split('.').map { it.toIntOrNull() ?: 0 }.let {
                    it[0] * 1000 +
                        it.getOrElse(1) { 0 }
                }
            }
    }
}

/** `/etc/ld.so.cache`, the `glibc-ld.so.cache1.1` format: soname → path. */
internal object LdSoCache {
    private const val MAGIC = "glibc-ld.so.cache1.1"
    private const val HEADER = 48
    private const val ENTRY = 24

    fun read(fs: ImageFs): Map<String, String>? {
        val bytes = fs.resolveFile("etc/ld.so.cache")?.second?.bytes ?: return null
        val start = String(bytes, Charsets.ISO_8859_1).indexOf(MAGIC)
        require(start >= 0) { "/etc/ld.so.cache is in a format this check does not read" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = linkedMapOf<String, String>()
        for (i in 0 until b.getInt(start + 20)) {
            val e = start + HEADER + i * ENTRY
            out.putIfAbsent(cString(bytes, start + b.getInt(e + 4)), cString(bytes, start + b.getInt(e + 8)))
        }
        return out
    }

    private fun cString(
        bytes: ByteArray,
        at: Int,
    ): String {
        var end = at
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        return String(bytes, at, end - at, Charsets.UTF_8)
    }
}
