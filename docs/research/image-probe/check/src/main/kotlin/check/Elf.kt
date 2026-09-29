package check

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What the dynamic loader reads from an ELF file before `main`, and nothing else: the interpreter it
 * is handed to, the libraries it asks for, where it says to look, and the symbol versions it needs
 * and defines.
 *
 * 64-bit little-endian only — the one layout `linuxX64` produces. Anything else is reported as
 * [NotElf] rather than half-read: a check that guessed at a layout it does not parse would print a
 * clean verdict for a file it never understood.
 */
class Elf private constructor(
    val interpreter: String?,
    val needed: List<String>,
    val soname: String?,
    val runpath: List<String>,
    /** file name (as written in NEEDED) → the versions this object needs from it */
    val versionNeeds: Map<String, List<VersionNeed>>,
    val versionsDefined: Set<String>,
    /** false for a static executable: no PT_DYNAMIC, nothing for the loader to do */
    val dynamic: Boolean,
) {
    data class VersionNeed(val name: String, val weak: Boolean)

    class NotElf(reason: String) : Exception(reason)

    companion object {
        private const val PT_LOAD = 1
        private const val PT_DYNAMIC = 2
        private const val PT_INTERP = 3
        private const val DT_NULL = 0L
        private const val DT_NEEDED = 1L
        private const val DT_STRTAB = 5L
        private const val DT_SONAME = 14L
        private const val DT_RPATH = 15L
        private const val DT_RUNPATH = 29L
        private const val DT_VERDEF = 0x6ffffffcL
        private const val DT_VERDEFNUM = 0x6ffffffdL
        private const val DT_VERNEED = 0x6ffffffeL
        private const val DT_VERNEEDNUM = 0x6fffffffL
        private const val VER_FLG_WEAK = 0x2

        fun parse(bytes: ByteArray): Elf {
            if (bytes.size < 64 || bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() ||
                bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
            ) {
                throw NotElf("no ELF magic")
            }
            if (bytes[4].toInt() != 2) throw NotElf("not ELF64")
            if (bytes[5].toInt() != 1) throw NotElf("not little-endian")
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            val phoff = b.getLong(0x20)
            val phentsize = b.getShort(0x36).toInt() and 0xffff
            val phnum = b.getShort(0x38).toInt() and 0xffff

            data class Load(val vaddr: Long, val offset: Long, val filesz: Long)
            val loads = mutableListOf<Load>()
            var interp: String? = null
            var dynOffset = -1L
            var dynSize = 0L
            for (i in 0 until phnum) {
                val p = (phoff + i.toLong() * phentsize).toInt()
                val type = b.getInt(p)
                val offset = b.getLong(p + 8)
                val vaddr = b.getLong(p + 16)
                val filesz = b.getLong(p + 32)
                when (type) {
                    PT_LOAD -> loads += Load(vaddr, offset, filesz)
                    PT_DYNAMIC -> {
                        dynOffset = offset
                        dynSize = filesz
                    }
                    PT_INTERP -> interp = cString(bytes, offset.toInt())
                }
            }
            if (dynOffset < 0) return Elf(interp, emptyList(), null, emptyList(), emptyMap(), emptySet(), false)

            // Dynamic entries hold VIRTUAL addresses; the file offset is found through the PT_LOAD
            // segment that maps them.
            fun fileOffset(vaddr: Long): Int {
                val l = loads.firstOrNull { vaddr >= it.vaddr && vaddr < it.vaddr + it.filesz }
                    ?: throw NotElf("address 0x${vaddr.toString(16)} is in no PT_LOAD segment")
                return (vaddr - l.vaddr + l.offset).toInt()
            }

            val entries = mutableListOf<Pair<Long, Long>>()
            var p = dynOffset.toInt()
            while (p + 16 <= dynOffset + dynSize) {
                val tag = b.getLong(p)
                val value = b.getLong(p + 8)
                if (tag == DT_NULL) break
                entries += tag to value
                p += 16
            }
            val strtab = entries.firstOrNull { it.first == DT_STRTAB }?.second
                ?: throw NotElf("dynamic section without DT_STRTAB")
            val str = fileOffset(strtab)
            fun string(off: Long) = cString(bytes, str + off.toInt())

            val needed = entries.filter { it.first == DT_NEEDED }.map { string(it.second) }
            val soname = entries.firstOrNull { it.first == DT_SONAME }?.let { string(it.second) }
            // DT_RUNPATH wins over DT_RPATH when both are present, as in the loader.
            val runpath = (entries.firstOrNull { it.first == DT_RUNPATH } ?: entries.firstOrNull { it.first == DT_RPATH })
                ?.let { string(it.second).split(':').filter(String::isNotEmpty) } ?: emptyList()

            val needs = linkedMapOf<String, MutableList<VersionNeed>>()
            entries.firstOrNull { it.first == DT_VERNEED }?.let { (_, addr) ->
                val count = entries.first { it.first == DT_VERNEEDNUM }.second.toInt()
                var e = fileOffset(addr)
                repeat(count) {
                    val cnt = b.getShort(e + 2).toInt() and 0xffff
                    val file = string(b.getInt(e + 4).toLong() and 0xffffffffL)
                    var a = e + b.getInt(e + 8)
                    repeat(cnt) {
                        val flags = b.getShort(a + 4).toInt() and 0xffff
                        val name = string(b.getInt(a + 8).toLong() and 0xffffffffL)
                        needs.getOrPut(file) { mutableListOf() } += VersionNeed(name, flags and VER_FLG_WEAK != 0)
                        a += b.getInt(a + 12)
                    }
                    e += b.getInt(e + 12)
                }
            }

            val defined = linkedSetOf<String>()
            entries.firstOrNull { it.first == DT_VERDEF }?.let { (_, addr) ->
                val count = entries.first { it.first == DT_VERDEFNUM }.second.toInt()
                var d = fileOffset(addr)
                repeat(count) {
                    // The first auxiliary entry names the version this entry defines; the rest are
                    // its parents.
                    val aux = d + b.getInt(d + 12)
                    defined += string(b.getInt(aux).toLong() and 0xffffffffL)
                    d += b.getInt(d + 16)
                }
            }

            return Elf(interp, needed, soname, runpath, needs, defined, true)
        }

        private fun cString(bytes: ByteArray, start: Int): String {
            var end = start
            while (end < bytes.size && bytes[end] != 0.toByte()) end++
            return String(bytes, start, end - start, Charsets.UTF_8)
        }
    }
}
