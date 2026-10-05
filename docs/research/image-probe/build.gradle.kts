plugins { kotlin("multiplatform") version "2.4.20" }

repositories { mavenCentral() }

/*
 * The smallest binaries that reproduce each corpus row, beside keel, which is the row that has to
 * serve. A hello-world asks the loader the same question keel does — static-probe measured that the
 * NEEDED list belongs to the runtime, not to the application — in seconds instead of minutes.
 *
 *   -PlinkMode=default    what the toolchain does unasked: ten NEEDED entries, libcrypt.so.1 among them
 *   -PlinkMode=asneeded   what sborka.kmp does: --as-needed, seven
 *   -PlinkMode=runpath    the default ten, plus a RUNPATH of $ORIGIN/lib (B-36)
 *
 * `probe` with no argument prints one line and exits 0: it loaded. `probe iconv` asks glibc for
 * UTF-8 → UTF-16LE, which glibc serves from a gconv module it loads with dlopen — the corpus's blind
 * spot, a failure no NEEDED entry can name.
 */
val linkMode = (findProperty("linkMode") as String?) ?: "default"

kotlin {
    linuxX64 {
        binaries.executable {
            entryPoint = "probe.main"
            baseName = "probe-$linkMode"
            when (linkMode) {
                "default" -> Unit
                "asneeded" -> linkerOpts("-Wl,--as-needed")
                // A library beside the binary, found only because the binary says where to look.
                "runpath" -> linkerOpts("-Wl,-rpath,\$ORIGIN/lib")
                else -> error("linkMode: default, asneeded or runpath; got $linkMode")
            }
        }
    }
}
