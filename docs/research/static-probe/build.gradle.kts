plugins { kotlin("multiplatform") version "2.4.10" }

repositories { mavenCentral() }

/*
 * One tiny linuxX64 executable, linked several ways.
 *
 * A hello-world is a fair subject for the LINKING question, and that is a measured claim rather than
 * a convenience: `readelf -d` gives the same ten NEEDED entries for tracy's server, katcher's server,
 * razves' CLI and this probe. That list belongs to the Kotlin/Native runtime, not to the application,
 * so the smallest program linking that runtime asks the same question as the largest — in three
 * seconds instead of three minutes.
 *
 * It is NOT a fair subject for the glibc floor. A server additionally imports `epoll_create1@2.9`,
 * `eventfd@2.7` and the weak `__cxa_thread_atexit_impl@2.18`; the probe stops at `memcpy@2.14`. The
 * floor numbers in the research come from the real binaries and say so.
 *
 *   -PlinkMode=default     what the toolchain does unasked
 *   -PlinkMode=asneeded    --as-needed, to drop the NEEDED entries nothing imports
 *   -PlinkMode=override    only the -Xoverride-konan-properties half of the circulating workaround
 *   -PlinkMode=recipe      the circulating workaround in full: --as-needed AND that override
 *   -PlinkMode=static      -static against the toolchain's own sysroot (RQ2)
 *   -PlinkMode=statichost  -static against the HOST's glibc 2.39, whose libc.a is complete
 *   -PlinkMode=musl        the same override pointed at a musl sysroot (RQ3, route 1)
 */
val linkMode = (findProperty("linkMode") as String?) ?: "default"

// Quoted from the workaround as it is passed around, not paraphrased.
val OVERRIDE_GCC_FLAGS = "-Xoverride-konan-properties=linkerGccFlags=-lgcc -lgcc_eh -lc"

kotlin {
    linuxX64 {
        binaries.executable {
            entryPoint = "probe.main"
            // `-Pgc=noop` switches the Kotlin/Native garbage collector off. It is here for exactly
            // one question, and it is a discriminating one: KT-85658 reports a GC deadlock on musl
            // and says `gc=noop` makes it go away. If the hang this probe records on a musl sysroot
            // is the same bug, it must answer to the same switch — and if it does not, it is a
            // second bug wearing the same symptom.
            val gc = findProperty("gc") as String?
            baseName = "probe-$linkMode" + (gc?.let { "-gc$it" } ?: "")
            if (gc != null) freeCompilerArgs += "-Xbinary=gc=$gc"
            when (linkMode) {
                "default" -> Unit
                // `-Wl,--as-needed` has to reach the linker BEFORE the `-l` flags it is meant to
                // filter, which is the whole reason this is an experiment rather than a one-line fix.
                "asneeded" -> linkerOpts("-Wl,--as-needed")
                // THE RECIPE THAT CIRCULATES, and its two halves separated. KT-55643 and KT-38876
                // both pass around `linkerOpts("--as-needed")` TOGETHER WITH
                // `-Xoverride-konan-properties=linkerGccFlags=-lgcc -lgcc_eh -lc`, as one cure. The
                // two halves reach different lists: the property replaces what konan.properties
                // contributes, and the six libraries this bug is about come from the `platform.posix`
                // klib's manifest, which no property names. Building the property half on its own is
                // what tells the two apart, and nothing in either ticket does.
                "override" -> freeCompilerArgs += OVERRIDE_GCC_FLAGS
                "recipe" -> {
                    linkerOpts("-Wl,--as-needed")
                    freeCompilerArgs += OVERRIDE_GCC_FLAGS
                }
                "static" -> linkerOpts("-static")
                // Route 1 of RQ3's two, and the reason it is tried on glibc first: if overriding
                // `targetSysRoot` cannot even reach the host's own libc, pointing it at a musl one
                // is not going to be the thing that works, and the failure will be easier to read
                // with a familiar libc on the other end.
                // THE LAST ROUTE INTO `scratch`, and the only one left after RQ2 failed: the host's
                // own glibc, which is 2.39 and ships a complete `libc.a`, instead of the 2.19 sysroot
                // the compiler brings. It needs five properties rather than one, for the same reason
                // the musl route did — the crt files, the gcc directory and the two flag lists all
                // still point into the toolchain, and `-lgcc_s` has no static archive anywhere.
                //
                // `--no-dynamic-linker` is not optional here either: `-dynamic-linker` is emitted
                // unconditionally, so without it this produces a static binary carrying a PT_INTERP.
                "statichost" -> {
                    val gccDir = (findProperty("hostGccDir") as String?)
                        ?: error("-PhostGccDir= is required, e.g. usr/lib/gcc/x86_64-linux-gnu/13")
                    val crtDir = (findProperty("hostCrtDir") as String?) ?: "usr/lib/x86_64-linux-gnu"
                    // `-L` IS NOT OPTIONAL EITHER. With the sysroot at `/` the linker is given
                    // `-L/lib`, `-L/usr/lib`, `-L/lib64`, `-L/usr/lib64` — the layout of the
                    // toolchain's own sysroot — and on a multiarch distribution every archive is in
                    // `/usr/lib/x86_64-linux-gnu`, which is in none of them. Without this the link
                    // fails with "unable to find library -lc", not with undefined symbols.
                    val libDir = (findProperty("hostLibDir") as String?) ?: "/usr/lib/x86_64-linux-gnu"
                    linkerOpts("-static", "--no-dynamic-linker", "-L$libDir")
                    freeCompilerArgs +=
                        "-Xoverride-konan-properties=" +
                        "targetSysRoot.linux_x64=/;" +
                        "crtFilesLocation.linux_x64=$crtDir;" +
                        "libGcc.linux_x64=$gccDir;" +
                        "linkerGccFlags=-lgcc -lgcc_eh -lc;" +
                        "linkerKonanFlags.linux_x64=-Bstatic -lstdc++ -lsupc++ " +
                        "--defsym __cxa_demangle=Konan_cxa_demangle"
                }
                // FOUR PROPERTIES, NOT ONE, and reading them was worth more than the first attempt.
                //
                // `targetSysRoot` alone leaves the crt paths pointing into the glibc toolchain, because
                // `libGcc` is its own key — documented in konan.properties as "targetSysroot-relative",
                // which is why the first attempt assembled a directory tree two levels deep instead of
                // setting it. `linkerGccFlags` is where `-lgcc_s` comes from, and musl has no archive
                // under that name; `linkerKonanFlags` is where `-lstdc++` comes from, and Alpine has a
                // musl-built one.
                //
                // What no property reaches: `-lresolv -lm -lpthread -lutil -lcrypt -lrt`, which live in
                // the `platform.posix` klib's manifest. Alpine happens to ship an archive for each, so
                // they resolve — on a sysroot that did not, nothing in a build file could help.
                "musl" -> {
                    val root = (findProperty("muslSysRoot") as String?) ?: error("-PmuslSysRoot= is required")
                    val libGcc = (findProperty("muslLibGcc") as String?) ?: "../gcc"
                    // `-PmuslLinker=` swaps in a shim that records the linker's argv and execs the
                    // real one. Reading the command is how the `PT_INTERP` in a `-static` binary
                    // stopped being a guess — the same property mechanism, used to look rather than
                    // to change.
                    val linker = (findProperty("muslLinker") as String?)
                    // `--no-dynamic-linker` is not belt and braces. The linker driver emits
                    // `-dynamic-linker /lib64/ld-linux-x86-64.so.2` — GLIBC's loader — before `-static`
                    // and regardless of the sysroot, and it is in none of the properties, so this is
                    // the only place it can be undone. Without it the kernel hands a statically linked
                    // musl binary to glibc's dynamic loader, which relocates it as though it were
                    // dynamic; the result is a segfault with no output, which is what the first
                    // attempt recorded as "musl links and does not run".
                    linkerOpts("-static", "--no-dynamic-linker")
                    freeCompilerArgs +=
                        "-Xoverride-konan-properties=" +
                        (linker?.let { "linker.linux_x64=$it;" } ?: "") +
                        "targetSysRoot.linux_x64=$root;" +
                        "libGcc.linux_x64=$libGcc;" +
                        "linkerGccFlags=-lgcc -lgcc_eh -lc;" +
                        "linkerKonanFlags.linux_x64=-Bstatic -lstdc++ -lsupc++ " +
                        "--defsym __cxa_demangle=Konan_cxa_demangle --gc-sections"
                }
                else -> error("linkMode: default, asneeded, static, statichost or musl; got $linkMode")
            }
        }
    }
}
