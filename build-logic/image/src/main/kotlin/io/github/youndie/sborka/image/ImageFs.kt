package io.github.youndie.sborka.image

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * The root filesystem a container would see: layers applied in order, whiteouts honoured, symlinks
 * followed INSIDE the image and never on the host — the one thing `lddtree -R` gets wrong on a base
 * whose interpreter is an absolute link (B-31).
 *
 * Every regular file's bytes are kept. A distroless base plus a service binary is tens of megabytes,
 * and keeping only "library-looking" paths would be a guess about where the loader looks — the one
 * thing this class must not guess.
 */
public class ImageFs {
    public sealed interface Node

    public class File(
        public val bytes: ByteArray,
    ) : Node

    public class Symlink(
        public val target: String,
    ) : Node

    public object Dir : Node

    private val nodes = linkedMapOf<String, Node>()

    /** Applies one layer blob: gzip or plain tar. zstd is refused by name rather than misread. */
    public fun apply(layer: ByteArray) {
        val input: InputStream =
            when {
                layer.size > 2 && layer[0] == 0x1f.toByte() && layer[1] == 0x8b.toByte() -> {
                    GZIPInputStream(ByteArrayInputStream(layer))
                }

                layer.size > 4 && layer[0] == 0x28.toByte() && layer[1] == 0xb5.toByte() -> {
                    throw IllegalArgumentException("zstd layer: not supported")
                }

                else -> {
                    ByteArrayInputStream(layer)
                }
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
                    remove((if (parent.isEmpty()) "" else "$parent/") + base.removePrefix(".wh."))
                    continue
                }
                when {
                    e.isDirectory -> {
                        // A directory replacing a file or link replaces it; one over a directory keeps
                        // what is inside.
                        if (nodes[path] !is Dir) remove(path)
                        nodes[path] = Dir
                    }

                    e.isSymbolicLink -> {
                        remove(path)
                        nodes[path] = Symlink(e.linkName)
                    }

                    e.isLink -> {
                        hardlinks += path to normalize(e.linkName)
                    }

                    e.isFile -> {
                        remove(path)
                        nodes[path] = File(tar.readAllBytes())
                    }
                }
            }
        }
        // A hard link names an entry of the same layer that may come later in the stream.
        for ((path, target) in hardlinks) nodes[target]?.let { nodes[path] = it }
    }

    /** Adds one file directly — the service binary a build is about to put on top of the base. */
    public fun put(
        path: String,
        bytes: ByteArray,
    ) {
        nodes[normalize(path)] = File(bytes)
    }

    /**
     * Resolves [path] component by component, following symlinks — in the middle of the path too,
     * since `/lib` → `usr/lib` is how a merged-/usr base answers for everything under it. Returns the
     * canonical path of a regular file, without a leading slash, or null when there is none.
     */
    public fun resolveFile(path: String): Pair<String, File>? {
        var hops = 0
        var pending = ArrayDeque(normalize(path).split('/').filter(String::isNotEmpty))
        val done = ArrayList<String>()
        while (pending.isNotEmpty()) {
            val part = pending.removeFirst()
            if (part == ".") continue
            if (part == "..") {
                if (done.isNotEmpty()) done.removeAt(done.size - 1)
                continue
            }
            val here = (done + part).joinToString("/")
            when (val n = nodes[here]) {
                is Symlink -> {
                    if (++hops > MAX_SYMLINKS) return null
                    if (n.target.startsWith("/")) done.clear()
                    pending = ArrayDeque(n.target.split('/').filter(String::isNotEmpty) + pending)
                }

                is File -> {
                    // a file used as a directory
                    if (pending.isNotEmpty()) return null
                    return here to n
                }

                // A directory need not have an entry of its own: tar streams often leave parents
                // implicit. Anything missing turns up as a missing final file.
                Dir, null -> {
                    done += part
                }
            }
        }
        return null
    }

    private fun remove(path: String) {
        nodes.remove(path)
        val prefix = "$path/"
        nodes.keys.filter { it.startsWith(prefix) }.forEach(nodes::remove)
    }

    private fun normalize(name: String): String =
        name.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")

    private companion object {
        /** The loader's own limit on a chain of links. */
        const val MAX_SYMLINKS = 40
    }
}
