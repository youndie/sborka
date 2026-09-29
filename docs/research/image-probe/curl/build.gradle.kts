plugins { kotlin("multiplatform") }

repositories { mavenCentral() }

/*
 * A module of its own so that ktor-client-curl's klib, whose manifest adds `-lz`, never reaches the
 * probe beside it. This binary exists for one corpus row: a dependency that brings a shared library
 * the older distroless base does not carry. Linked --as-needed, as sborka.kmp links, so `libz.so.1`
 * is in NEEDED only because something in libcurl really calls it.
 */
val linkMode = (findProperty("linkMode") as String?) ?: "asneeded"

kotlin {
    linuxX64 {
        binaries.executable {
            entryPoint = "probe.main"
            baseName = "curl-$linkMode"
            if (linkMode == "asneeded") linkerOpts("-Wl,--as-needed")
        }
    }
    sourceSets {
        linuxX64Main.dependencies { implementation("io.ktor:ktor-client-curl:3.6.0") }
    }
}
