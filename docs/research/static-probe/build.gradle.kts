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
                // The sysroot has to be BUILT, not pointed at: the toolchain looks for `crt1.o` under
                // `<sysroot>/usr/lib` and for `crtbegin.o` under `<sysroot>/../../lib/gcc/<triple>/8.3.0`,
                // so a distribution's musl directory cannot be used as-is whatever the property says.
                // `-PmuslSysRoot=` names one assembled to that shape; `experiments.sh` assembles it.
                "musl" -> {
                    val root = (findProperty("muslSysRoot") as String?) ?: error("-PmuslSysRoot= is required")
                    linkerOpts("-static")
                    freeCompilerArgs += "-Xoverride-konan-properties=targetSysRoot.linux_x64=$root"
                }
                else -> error("linkMode: default, asneeded, static, statichost or musl; got $linkMode")
            }
        }
    }
}
