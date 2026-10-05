plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("io.github.youndie.sborka.kmp")
}

// A LIBRARY WITH A C BINDING, for `sborka.kmp`'s refusal of a klib a consumer cannot link (sborka#116).
//
// The binding is one header and one function in an archive of its own, `libstandc.a`, built below from
// `src/nativeInterop/c`. `-Pstand.cinterop` picks where the archive is named, and only the default is
// part of the stand's `check`; the other three are the controls CI runs (.github/workflows/check.yaml):
//
//   def        (default) `staticLibraries` in the .def: the klib carries the archive, and this
//              module's test links with no options of its own. Green.
//   extraOpts  `extraOpts("-staticLibrary", …)`: the archive is in included/ and the manifest names
//              nothing, so a check that read the manifest would call it empty. Green.
//   binary     the archive on this module's own binaries, `binaries.all { linkerOpts(…) }`: the shape
//              kafkakn B-15 and smtpkn M-110 shipped. Refused at configuration.
//   bare       the archive named nowhere: `verifyCinteropKlibs` refuses the klib.
//
// linuxX64 only: the archive holds an x86-64 ELF object, compiled by the host's `cc` - natively on a
// Linux runner, with `--target` on a Mac, which links the test binary there and cannot run it.
val shape = providers.gradleProperty("stand.cinterop").getOrElse("def")
require(shape in setOf("def", "extraOpts", "binary", "bare")) { "stand.cinterop=$shape: one of def, extraOpts, binary, bare" }

val cSources = layout.projectDirectory.dir("src/nativeInterop/c")
val archiveDir = layout.buildDirectory.dir("standc")
val onMac = System.getProperty("os.name").startsWith("Mac")

val compileStandc =
    tasks.register<Exec>("compileStandc") {
        val source = cSources.file("standc.c")
        val objectFile = archiveDir.map { it.file("standc.o") }
        inputs.file(source)
        outputs.file(objectFile)
        val target = if (onMac) listOf("--target=x86_64-unknown-linux-gnu") else emptyList()
        commandLine(listOf("cc") + target + listOf("-fPIC", "-O2", "-c", source.asFile.path, "-o", objectFile.get().asFile.path))
    }

// THE ARCHIVE IS WRITTEN HERE, NOT BY `ar`: the Mac's `ar` keeps only Mach-O members and turned the ELF
// object into a 96-byte archive holding a symbol table and nothing else. A GNU archive of one member is
// eight bytes of magic and a 60-byte header; ld.lld needs no symbol index in it.
val archiveStandc =
    tasks.register("archiveStandc") {
        val objectFile = archiveDir.map { it.file("standc.o") }
        val archive = archiveDir.map { it.file("libstandc.a") }
        inputs.files(compileStandc)
        outputs.file(archive)
        doLast {
            val member = objectFile.get().asFile.readBytes()
            val header =
                "standc.o/".padEnd(16) + "0".padEnd(12) + "0".padEnd(6) + "0".padEnd(6) + "644".padEnd(8) +
                    member.size.toString().padEnd(10) + "`\n"
            archive.get().asFile.outputStream().use { out ->
                out.write("!<arch>\n".toByteArray())
                out.write(header.toByteArray())
                out.write(member)
                if (member.size % 2 == 1) out.write('\n'.code)
            }
        }
    }

kotlin {
    linuxX64 {
        val interop =
            compilations.getByName("main").cinterops.create("standc") {
                includeDirs(cSources)
                when (shape) {
                    "def" -> {
                        definitionFile.set(file("src/nativeInterop/cinterop/standc.def"))
                        extraOpts("-libraryPath", archiveDir.get().asFile.path)
                    }

                    "extraOpts" -> {
                        definitionFile.set(file("src/nativeInterop/cinterop/standc-bare.def"))
                        extraOpts("-staticLibrary", "libstandc.a", "-libraryPath", archiveDir.get().asFile.path)
                    }

                    else -> {
                        definitionFile.set(file("src/nativeInterop/cinterop/standc-bare.def"))
                    }
                }
            }
        tasks.named(interop.interopProcessingTaskName) { inputs.files(archiveStandc) }

        if (shape == "binary") {
            binaries.all { linkerOpts("-L${archiveDir.get().asFile.path}", "-lstandc") }
        }
    }
}
