package io.github.youndie.sborka.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The reading behind sborka#116, against option lists, `.def` texts and klib layouts taken from the
 * portfolio's five bindings rather than invented: kafkakn's `rdkafka.def` (archives), mongkn's and
 * smtpkn's generated `.def` (a shared library), razves's `sampler.def` and kore's `koreSignal.def`
 * (shims with no `headers`).
 *
 * Every mistake here is quiet in one direction or loud in the wrong place. Too narrow, and the shape
 * that shipped two unlinkable klibs passes again; too wide, and `-Wl,--as-needed` — which
 * `sborka.kmp` puts on every Linux executable itself — fails every module with a binding.
 */
class CinteropLinkTest {
    @Test
    fun `what names a library on a binary is refused`() {
        assertEquals(
            listOf("-lssl", "-lcrypto", "-L/usr/lib/x86_64-linux-gnu", "/cache/librdkafka-static.a", "libssl.so.3"),
            CinteropLink.librariesNamed(
                listOf("-lssl", "-lcrypto", "-L/usr/lib/x86_64-linux-gnu", "/cache/librdkafka-static.a", "libssl.so.3"),
            ),
        )
    }

    @Test
    fun `what sborka adds itself and options that name no library stay allowed`() {
        assertEquals(
            emptyList<String>(),
            CinteropLink.librariesNamed(
                listOf(
                    "-Wl,--as-needed",
                    "-Wl,--allow-shlib-undefined",
                    "--allow-shlib-undefined",
                    "-static",
                    "-Wl,-rpath,/opt/lib",
                ),
            ),
        )
    }

    @Test
    fun `a library inside a -Wl list or split from its flag is still a library`() {
        assertEquals(listOf("-Wl,--as-needed,-lfoo"), CinteropLink.librariesNamed(listOf("-Wl,--as-needed,-lfoo")))
        assertEquals(
            listOf("-l ssl", "-L /opt/lib", "-framework Security"),
            CinteropLink.librariesNamed(listOf("-l", "ssl", "-L", "/opt/lib", "-framework", "Security")),
        )
    }

    @Test
    fun `headers are read from the properties part only and per target`() {
        val kafkakn =
            "# the C surface\nheaders = librdkafka/rdkafka.h\nheaderFilter = librdkafka/**\n" +
                "staticLibraries = librdkafka-static.a libssl.a\n"
        assertTrue(CinteropLink.declaresHeaders(kafkakn, "linux_x64", "linux"))

        // razves: C after `---`, a per-platform -lrt, no headers.
        val razves =
            "package = razves.sampler.native\nlinkerOpts.linux = -lrt\n---\n" +
                "#include <signal.h>\nheaders = not a key here\n"
        assertFalse(CinteropLink.declaresHeaders(razves, "linux_x64", "linux"))

        val perPlatform = "headers.osx = foo.h\n"
        assertTrue(CinteropLink.declaresHeaders(perPlatform, "macos_arm64", "osx"))
        assertFalse(CinteropLink.declaresHeaders(perPlatform, "linux_x64", "linux"))
        assertTrue(CinteropLink.declaresHeaders("headers.linux_x64 = foo.h\n", "linux_x64", "linux"))
    }

    @Test
    fun `the B-15 signature is refused and every way of carrying the link passes`() {
        val empty = CinteropLink.Carried(emptyMap(), emptyList(), emptyList())
        assertNotNull(CinteropLink.verdict(declaresHeaders = true, linksNothingBecause = null, carried = empty))

        // kafkakn: archives in included/, and the manifest names them.
        val archives =
            CinteropLink.Carried(
                mapOf("librdkafka-static.a" to 1L),
                emptyList(),
                listOf("librdkafka-static.a"),
            )
        assertNull(CinteropLink.verdict(true, null, archives))

        // `-staticLibrary` in extraOpts: the archive is there, the manifest key is not.
        val noKey = CinteropLink.Carried(mapOf("libz3.a" to 1L), emptyList(), emptyList())
        assertNull(CinteropLink.verdict(true, null, noKey))

        // mongkn / smtpkn: a shared library through the generated .def.
        val shared =
            CinteropLink.Carried(
                emptyMap(),
                listOf("--allow-shlib-undefined", "-L/usr/lib/x86_64-linux-gnu", "-lssl", "-lcrypto"),
                emptyList(),
            )
        assertNull(CinteropLink.verdict(true, null, shared))

        // Only a flag that names no library is not a link.
        val flagOnly = CinteropLink.Carried(emptyMap(), listOf("--allow-shlib-undefined"), emptyList())
        assertNotNull(CinteropLink.verdict(true, null, flagOnly))

        // kore: a shim with no headers has nothing to carry.
        assertNull(CinteropLink.verdict(false, null, empty))
    }

    @Test
    fun `the marker passes headers that link nothing only with a reason and only while it is true`() {
        val def = "# sborka: links nothing: constants and inline functions only\nheaders = linux/io_uring.h\n"
        val because = CinteropLink.linksNothingBecause(def)
        assertEquals("constants and inline functions only", because)
        val empty = CinteropLink.Carried(emptyMap(), emptyList(), emptyList())
        assertNull(CinteropLink.verdict(true, because, empty))

        assertEquals("", CinteropLink.linksNothingBecause("# sborka: links nothing\nheaders = a.h\n"))
        assertNotNull(CinteropLink.verdict(true, "", empty))

        val carrying = CinteropLink.Carried(emptyMap(), listOf("-lfoo"), emptyList())
        assertNotNull(CinteropLink.verdict(true, because, carrying))

        assertNull(
            CinteropLink.linksNothingBecause("headers = a.h\n---\n# sborka: links nothing: in C, not a comment\n"),
        )
    }

    @Test
    fun `a packed klib is read from included and the manifest`(
        @TempDir dir: File,
    ) {
        val klib = File(dir, "lib-cinterop-z.klib")
        ZipOutputStream(klib.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("default/"))
            zip.putNextEntry(ZipEntry("default/manifest"))
            zip.write("unique_name=z\nlinkerOpts=-L/opt/z -lz\n".toByteArray())
            zip.putNextEntry(ZipEntry("default/targets/linux_x64/included/"))
            zip.putNextEntry(ZipEntry("default/targets/linux_x64/included/libz3.a"))
            zip.write(ByteArray(7))
            zip.putNextEntry(ZipEntry("default/linkdata/manifest"))
            zip.write("linkerOpts=-lwrong\n".toByteArray())
        }
        val carried = CinteropLink.read(klib, "linux_x64", "linux")
        assertEquals(mapOf("libz3.a" to 7L), carried.archives)
        assertEquals(listOf("-L/opt/z", "-lz"), carried.linkerOpts)
        assertEquals(emptyList<String>(), carried.staticLibraries)
        assertTrue(carried.carriesALink)
    }

    @Test
    fun `an unpacked klib is read the same way`(
        @TempDir dir: File,
    ) {
        val klib = File(dir, "lib-cinterop-z")
        File(klib, "default").mkdirs()
        File(klib, "default/manifest").writeText("unique_name=z\n")
        assertFalse(CinteropLink.read(klib, "linux_x64", "linux").carriesALink)

        File(klib, "default/targets/linux_x64/included").mkdirs()
        File(klib, "default/targets/linux_x64/included/libz3.a").writeBytes(ByteArray(3))
        assertEquals(mapOf("libz3.a" to 3L), CinteropLink.read(klib, "linux_x64", "linux").archives)
    }

    @Test
    fun `per-platform keys stay in the manifest and count for their own targets only`(
        @TempDir dir: File,
    ) {
        // What cinterop wrote for razves's `linkerOpts.linux = -lrt`, on both of its targets.
        val klib = File(dir, "sampler-cinterop-sampler")
        File(klib, "default").mkdirs()
        File(klib, "default/manifest").writeText("interop=true\nlinkerOpts.linux=-lrt\n")
        assertEquals(listOf("-lrt"), CinteropLink.read(klib, "linux_x64", "linux").linkerOpts)
        assertEquals(emptyList<String>(), CinteropLink.read(klib, "macos_arm64", "osx").linkerOpts)

        File(klib, "default/manifest").writeText("interop=true\nlinkerOpts=-lz\nlinkerOpts.linux_x64=-lrt\n")
        assertEquals(listOf("-lz", "-lrt"), CinteropLink.read(klib, "linux_x64", "linux").linkerOpts)
    }
}
