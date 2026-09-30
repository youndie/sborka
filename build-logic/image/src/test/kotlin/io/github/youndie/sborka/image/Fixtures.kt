package io.github.youndie.sborka.image

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

/**
 * The smallest ELF64 the loader questions can be asked of, written byte by byte: one PT_LOAD mapping
 * the whole file at address 0 (so an address IS a file offset), an optional PT_INTERP, a PT_DYNAMIC
 * with NEEDED / SONAME / RUNPATH / VERNEED / VERDEF. Built here rather than committed, so that every
 * byte a test depends on is in the review.
 */
internal fun elf(
    interpreter: String? = "/lib64/ld-linux-x86-64.so.2",
    needed: List<String> = emptyList(),
    soname: String? = null,
    runpath: String? = null,
    needs: Map<String, List<Pair<String, Boolean>>> = emptyMap(),
    defines: List<String> = emptyList(),
    dynamic: Boolean = true,
): ByteArray {
    val strings = ByteArrayOutputStream().apply { write(0) }
    val offsets = HashMap<String, Int>()

    fun str(s: String): Int =
        offsets.getOrPut(s) {
            val at = strings.size()
            strings.write(s.toByteArray())
            strings.write(0)
            at
        }

    val neededOffsets = needed.map(::str)
    val sonameOffset = soname?.let(::str)
    val runpathOffset = runpath?.let(::str)
    val interpBytes = interpreter?.toByteArray()?.plus(0)

    val verneed = ByteArrayOutputStream()
    needs.entries.forEachIndexed { i, (file, versions) ->
        val last = i == needs.size - 1
        verneed.write(
            le(16) {
                putShort(1)
                putShort(versions.size.toShort())
                putInt(str(file))
                putInt(16)
                putInt(if (last) 0 else 16 + 16 * versions.size)
            },
        )
        versions.forEachIndexed { j, (name, weak) ->
            verneed.write(
                le(16) {
                    putInt(0)
                    putShort(if (weak) 2 else 0)
                    putShort(0)
                    putInt(str(name))
                    putInt(if (j == versions.size - 1) 0 else 16)
                },
            )
        }
    }
    val verdef = ByteArrayOutputStream()
    defines.forEachIndexed { i, name ->
        verdef.write(
            le(20) {
                putShort(1)
                putShort(0)
                putShort((i + 1).toShort())
                putShort(1)
                putInt(0)
                putInt(20)
                putInt(if (i == defines.size - 1) 0 else 28)
            },
        )
        verdef.write(
            le(8) {
                putInt(str(name))
                putInt(0)
            },
        )
    }

    val phnum = 1 + (if (interpBytes != null) 1 else 0) + (if (dynamic) 1 else 0)
    val interpAt = 64 + 56 * phnum
    val strAt = interpAt + (interpBytes?.size ?: 0)
    val strBytes = strings.toByteArray()
    val verneedAt = strAt + strBytes.size
    val verdefAt = verneedAt + verneed.size()
    val dynAt = (verdefAt + verdef.size() + 7) / 8 * 8

    val dyn = mutableListOf<Pair<Long, Long>>()
    neededOffsets.forEach { dyn += 1L to it.toLong() }
    sonameOffset?.let { dyn += 14L to it.toLong() }
    runpathOffset?.let { dyn += 29L to it.toLong() }
    dyn += 5L to strAt.toLong()
    if (needs.isNotEmpty()) {
        dyn += 0x6ffffffeL to verneedAt.toLong()
        dyn += 0x6fffffffL to needs.size.toLong()
    }
    if (defines.isNotEmpty()) {
        dyn += 0x6ffffffcL to verdefAt.toLong()
        dyn += 0x6ffffffdL to defines.size.toLong()
    }
    dyn += 0L to 0L
    val dynBytes = le(16 * dyn.size) { dyn.forEach { (t, v) -> putLong(t).putLong(v) } }
    val size = dynAt + dynBytes.size

    val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    out.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1))
    out.putShort(0x10, 3).putShort(0x12, 0x3e).putInt(0x14, 1)
    out
        .putLong(0x20, 64)
        .putShort(0x34, 64)
        .putShort(0x36, 56)
        .putShort(0x38, phnum.toShort())
    var ph = 64

    fun phdr(
        type: Int,
        offset: Int,
        length: Int,
    ) {
        out.putInt(ph, type).putLong(ph + 8, offset.toLong()).putLong(ph + 16, offset.toLong())
        out.putLong(ph + 32, length.toLong()).putLong(ph + 40, length.toLong())
        ph += 56
    }
    phdr(1, 0, size)
    if (interpBytes != null) phdr(3, interpAt, interpBytes.size)
    if (dynamic) phdr(2, dynAt, dynBytes.size)
    interpBytes?.let { out.put(interpAt, it) }
    out.put(strAt, strBytes)
    out.put(verneedAt, verneed.toByteArray())
    out.put(verdefAt, verdef.toByteArray())
    if (dynamic) out.put(dynAt, dynBytes)
    return out.array()
}

private fun le(
    size: Int,
    fill: ByteBuffer.() -> Unit,
): ByteArray =
    ByteBuffer
        .allocate(size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply(fill)
        .array()

/** One layer as a gzip tar stream, the way a registry serves it. */
internal class Layer {
    private val bytes = ByteArrayOutputStream()
    private val tar =
        TarArchiveOutputStream(GZIPOutputStream(bytes)).apply {
            setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
        }

    fun file(
        path: String,
        content: ByteArray,
    ) = apply {
        val e = TarArchiveEntry(path).apply { size = content.size.toLong() }
        tar.putArchiveEntry(e)
        tar.write(content)
        tar.closeArchiveEntry()
    }

    fun symlink(
        path: String,
        target: String,
    ) = apply {
        tar.putArchiveEntry(TarArchiveEntry(path, TarArchiveEntry.LF_SYMLINK).apply { linkName = target })
        tar.closeArchiveEntry()
    }

    fun whiteout(path: String) =
        file(
            path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } + ".wh." +
                path.substringAfterLast('/'),
            ByteArray(0),
        )

    fun opaque(dir: String) = file("$dir/.wh..wh..opq", ByteArray(0))

    fun build(): ByteArray {
        tar.close()
        return bytes.toByteArray()
    }
}

/** `/etc/ld.so.cache` in the `glibc-ld.so.cache1.1` layout, with the given soname → path entries. */
internal fun ldSoCache(entries: Map<String, String>): ByteArray {
    val strings = ByteArrayOutputStream()
    val base = 48 + 24 * entries.size
    val refs =
        entries.map { (k, v) ->
            val key = base + strings.size()
            strings.write(k.toByteArray() + 0)
            val value = base + strings.size()
            strings.write(v.toByteArray() + 0)
            key to value
        }
    return le(base) {
        put("glibc-ld.so.cache1.1".toByteArray())
        putInt(entries.size)
        putInt(strings.size())
        position(48)
        refs.forEach { (k, v) ->
            putInt(0x0303)
            putInt(k)
            putInt(v)
            putInt(0)
            putLong(0)
        }
    } + strings.toByteArray()
}
