package io.github.youndie.sborka.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Each loader rule the check follows, on synthetic ELF files and layers. The corpus in
 * `docs/research/image-probe/` asks the same questions of real binaries and real bases, with
 * `docker run` as the answer; these pin the rules down where a regression can be read in one line.
 */
class LoadCheckTest {
    private val loader = "lib/x86_64-linux-gnu/ld-linux-x86-64.so.2"
    private val libc =
        elf(interpreter = null, soname = "libc.so.6", defines = listOf("GLIBC_2.2.5", "GLIBC_2.17", "GLIBC_2.36"))

    /** A Debian-shaped base: merged /usr, the loader reached through an ABSOLUTE link, libc. */
    private fun base(vararg extra: Pair<String, ByteArray>): ImageFs =
        ImageFs().apply {
            apply(
                Layer()
                    .symlink("lib", "usr/lib")
                    .file("usr/$loader", elf(interpreter = null, soname = "ld-linux-x86-64.so.2"))
                    .symlink("lib64/ld-linux-x86-64.so.2", "/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2")
                    .file("usr/lib/x86_64-linux-gnu/libc.so.6", libc)
                    .apply { extra.forEach { (p, b) -> file(p, b) } }
                    .build(),
            )
        }

    private fun check(
        fs: ImageFs,
        env: Map<String, String> = emptyMap(),
    ) = LoadCheck(fs, env).run("/app/bin")

    @Test
    fun `a binary whose libraries are all there loads, through an absolute link to the interpreter`() {
        val fs = base().apply { put("app/bin", elf(needed = listOf("libc.so.6"))) }
        val r = check(fs)
        assertEquals("loads", r.verdict)
        assertTrue(
            r.resolved.any {
                it.startsWith("interpreter /lib64/ld-linux-x86-64.so.2 -> /usr/$loader")
            },
            r.resolved.toString(),
        )
    }

    @Test
    fun `a missing library is named with the object that asked for it`() {
        val fs = base().apply { put("app/bin", elf(needed = listOf("libc.so.6", "libgcc_s.so.1"))) }
        assertEquals("missing-library libgcc_s.so.1 needed by /app/bin", check(fs).verdict)
    }

    @Test
    fun `no interpreter stops the check there, because nothing else would run`() {
        val fs = ImageFs().apply { put("app/bin", elf(needed = listOf("libc.so.6", "libm.so.6"))) }
        val r = check(fs)
        assertEquals("missing-interpreter /lib64/ld-linux-x86-64.so.2", r.verdict)
        assertEquals(1, r.problems.size)
    }

    @Test
    fun `a static binary has nothing for a loader to do`() {
        val fs = ImageFs().apply { put("app/bin", elf(interpreter = null, dynamic = false)) }
        assertEquals("loads", check(fs).verdict)
    }

    @Test
    fun `a library found transitively is checked for its own needs`() {
        val crypt = elf(interpreter = null, needed = listOf("libc.so.6", "libgone.so.1"), soname = "libcrypt.so.1")
        val fs =
            base("usr/lib/x86_64-linux-gnu/libcrypt.so.1" to crypt).apply {
                put("app/bin", elf(needed = listOf("libcrypt.so.1")))
            }
        assertEquals(
            "missing-library libgone.so.1 needed by /usr/lib/x86_64-linux-gnu/libcrypt.so.1",
            check(fs).verdict,
        )
    }

    @Test
    fun `a version the provider does not define fails, naming the highest it does`() {
        val crypt =
            elf(
                interpreter = null,
                needed = listOf("libc.so.6"),
                needs =
                    mapOf("libc.so.6" to listOf("GLIBC_2.38" to false)),
            )
        val fs =
            base("usr/lib/x86_64-linux-gnu/libcrypt.so.1" to crypt).apply {
                put("app/bin", elf(needed = listOf("libcrypt.so.1")))
            }
        val r = check(fs)
        assertEquals(
            "missing-version GLIBC_2.38 from libc.so.6 needed by /usr/lib/x86_64-linux-gnu/libcrypt.so.1",
            r.verdict,
        )
        assertTrue(
            r.problems
                .single()
                .line
                .endsWith("it defines up to GLIBC_2.36"),
            r.problems.single().line,
        )
    }

    @Test
    fun `a missing WEAK version is a note, as the loader allows it`() {
        val bin =
            elf(
                needed = listOf("libc.so.6"),
                needs =
                    mapOf("libc.so.6" to listOf("GLIBC_2.17" to false, "GLIBC_2.99" to true)),
            )
        val r = check(base().apply { put("app/bin", bin) })
        assertEquals("loads", r.verdict)
        assertTrue(r.notes.any { "weak version GLIBC_2.99" in it }, r.notes.toString())
    }

    @Test
    fun `RUNPATH with ORIGIN finds a library beside the binary, and only for the binary that says so`() {
        val lib = elf(interpreter = null, soname = "libside.so.1")
        val with =
            base("app/lib/libside.so.1" to lib).apply {
                put("app/bin", elf(needed = listOf("libside.so.1"), runpath = "\$ORIGIN/lib"))
            }
        val without = base("app/lib/libside.so.1" to lib).apply { put("app/bin", elf(needed = listOf("libside.so.1"))) }
        assertEquals("loads", check(with).verdict)
        assertEquals("missing-library libside.so.1 needed by /app/bin", check(without).verdict)
    }

    @Test
    fun `LD_LIBRARY_PATH from the image config is searched`() {
        val fs =
            base(
                "opt/x/libx.so.1" to elf(interpreter = null),
            ).apply { put("app/bin", elf(needed = listOf("libx.so.1"))) }
        assertEquals("loads", check(fs, mapOf("LD_LIBRARY_PATH" to "/opt/x")).verdict)
        assertEquals("missing-library libx.so.1 needed by /app/bin", check(fs).verdict)
    }

    @Test
    fun `ld so cache places a library no default directory holds, and a stale entry places nothing`() {
        val lib = "opt/x/libx.so.1" to elf(interpreter = null)
        val placed = base(lib, "etc/ld.so.cache" to ldSoCache(mapOf("libx.so.1" to "/opt/x/libx.so.1")))
        val stale = base(lib, "etc/ld.so.cache" to ldSoCache(mapOf("libx.so.1" to "/opt/gone/libx.so.1")))
        placed.put("app/bin", elf(needed = listOf("libx.so.1")))
        stale.put("app/bin", elf(needed = listOf("libx.so.1")))
        assertEquals("loads", check(placed).verdict)
        assertEquals("missing-library libx.so.1 needed by /app/bin", check(stale).verdict)
    }

    @Test
    fun `a whiteout in a later layer removes what a lower layer carried`() {
        val fs = base("usr/lib/x86_64-linux-gnu/libgcc_s.so.1" to elf(interpreter = null))
        fs.put("app/bin", elf(needed = listOf("libc.so.6", "libgcc_s.so.1")))
        assertEquals("loads", check(fs).verdict)
        fs.apply(Layer().whiteout("usr/lib/x86_64-linux-gnu/libgcc_s.so.1").build())
        assertEquals("missing-library libgcc_s.so.1 needed by /app/bin", check(fs).verdict)
    }

    @Test
    fun `an opaque directory hides everything the lower layers had under it`() {
        val fs = base("opt/x/libx.so.1" to elf(interpreter = null))
        fs.put("app/bin", elf(needed = listOf("libx.so.1")))
        val env = mapOf("LD_LIBRARY_PATH" to "/opt/x")
        assertEquals("loads", check(fs, env).verdict)
        fs.apply(Layer().opaque("opt/x").build())
        assertEquals("missing-library libx.so.1 needed by /app/bin", check(fs, env).verdict)
    }
}
