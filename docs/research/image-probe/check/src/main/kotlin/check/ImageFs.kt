package check

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * The root filesystem a container would see: layers applied in order, whiteouts honoured, symlinks
 * followed INSIDE the image and never on the host.
 *
 * Every regular file's bytes are kept. A distroless base plus a service binary is tens of
 * megabytes, and reading only "library-looking" paths would be a guess about where the loader looks
 * — the one thing this class must not guess.
 */
class ImageFs {
    sealed interface Node
    class File(val bytes: ByteArray, val mode: Int) : Node
    class Symlink(val target: String) : Node
    object Dir : Node

    private val nodes = linkedMapOf<String, Node>()

    /** Applies one layer blob: gzip or plain tar. zstd is refused by name rather than misread. */
    fun apply(layer: ByteArray) {
        val input: InputStream =
            when {
                layer.size > 2 && layer[0] == 0x1f.toByte() && layer[1] == 0x8b.toByte() ->
                    GZIPInputStream(ByteArrayInputStream(layer))
                layer.size > 4 && layer[0] == 0x28.toByte() && layer[1] == 0xb5.toByte() ->
                    throw IllegalArgumentException("zstd layer: not supported by this prototype")
                else -> ByteArrayInputStream(layer)
            }
        val hardlinks = mutableListOf<Pair<String, String>>()
        TarArchiveInputStream(input).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val path = normalize(e.name)
                if (path.isEmpty()) continue
                val base = path.substringAfterLast('/')
                val parent = path.substringBeforeLast('/', "")
                // OPAQUE: everything the lower layers had under this directory is gone.
                if (base == ".wh..wh..opq") {
                    val prefix = if (parent.isEmpty()) "" else "$parent/"
                    nodes.keys.filter { it.startsWith(prefix) && it != parent }.forEach(nodes::remove)
                    continue
                }
                if (base.startsWith(".wh.")) {
                    val gone = (if (parent.isEmpty()) "" else "$parent/") + base.removePrefix(".wh.")
                    remove(gone)
                    continue
                }
                when {
                    e.isDirectory -> {
                        // A directory replacing a file or link replaces it; one over a directory
                        // keeps what is inside.
                        if (nodes[path] !is Dir) remove(path)
                        nodes[path] = Dir
                    }
                    e.isSymbolicLink -> {
                        remove(path)
                        nodes[path] = Symlink(e.linkName)
                    }
                    e.isLink -> hardlinks += path to normalize(e.linkName)
                    e.isFile -> {
                        remove(path)
                        nodes[path] = File(tar.readAllBytes(), e.mode)
                    }
                }
            }
        }
        // A hard link names an entry of the same layer that may come later in the stream.
        for ((path, target) in hardlinks) nodes[target]?.let { nodes[path] = it }
    }

    /** Adds one file directly — the service binary a build is about to put on top of the base. */
    fun put(path: String, bytes: ByteArray, mode: Int = 0x1ed) {
        nodes[normalize(path)] = File(bytes, mode)
    }

    private fun remove(path: String) {
        nodes.remove(path)
        val prefix = "$path/"
        nodes.keys.filter { it.startsWith(prefix) }.forEach(nodes::remove)
    }

    fun has(path: String) = nodes.containsKey(normalize(path))

    /**
     * Resolves [path] component by component, following symlinks — in the middle of the path too,
     * since `/lib` → `usr/lib` is how a merged-/usr base answers for everything under it. Returns the
     * canonical path of a regular file, or null when there is none.
     */
    fun resolveFile(path: String): Pair<String, File>? {
        var hops = 0
        var pending = ArrayDeque(normalize(path).split('/').filter(String::isNotEmpty))
        val done = ArrayList<String>()
        while (pending.isNotEmpty()) {
            val part = pending.removeFirst()
            when (part) {
                "." -> continue
                ".." -> {
                    if (done.isNotEmpty()) done.removeAt(done.size - 1)
                    continue
                }
            }
            val here = (done + part).joinToString("/")
            when (val n = nodes[here]) {
                is Symlink -> {
                    if (++hops > 40) return null
                    val target = n.target.split('/').filter(String::isNotEmpty)
                    if (n.target.startsWith("/")) done.clear()
                    pending = ArrayDeque(target + pending)
                }
                is File -> {
                    if (pending.isNotEmpty()) return null // a file used as a directory
                    return here to n
                }
                Dir, null -> {
                    // A directory need not have an entry of its own: tar streams often leave the
                    // parents implicit. Anything missing turns up as a missing final file.
                    done += part
                }
            }
        }
        return null
    }

    private fun normalize(name: String): String =
        name.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
}
