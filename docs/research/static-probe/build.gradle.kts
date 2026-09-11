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
 *   -PlinkMode=static      -static against the toolchain's own sysroot (RQ2)
 *   -PlinkMode=statichost  -static against the host's newer glibc, by overriding targetSysRoot
 *   -PlinkMode=musl        the same override pointed at a musl sysroot (RQ3, route 1)
 */
val linkMode = (findProperty("linkMode") as String?) ?: "default"

kotlin {
    linuxX64 {
        binaries.executable {
            entryPoint = "probe.main"
            baseName = "probe-$linkMode"
            when (linkMode) {
                "default" -> Unit
                // `-Wl,--as-needed` has to reach the linker BEFORE the `-l` flags it is meant to
                // filter, which is the whole reason this is an experiment rather than a one-line fix.
                "asneeded" -> linkerOpts("-Wl,--as-needed")
                "static" -> linkerOpts("-static")
                // Route 1 of RQ3's two, and the reason it is tried on glibc first: if overriding
                // `targetSysRoot` cannot even reach the host's own libc, pointing it at a musl one
                // is not going to be the thing that works, and the failure will be easier to read
                // with a familiar libc on the other end.
                "statichost" -> {
                    linkerOpts("-static")
                    freeCompilerArgs += "-Xoverride-konan-properties=targetSysRoot.linux_x64=/"
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
