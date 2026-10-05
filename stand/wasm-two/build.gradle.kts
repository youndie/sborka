plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("io.github.youndie.sborka.kmp")
}

// ONE OF TWO WASM EXECUTABLES, and the pair is the whole point: `sborka.base` queues Kotlin/Wasm
// and Kotlin/JS links through one slot per build (sborka#132), and a queue of one proves nothing.
// The root's `verifyWebLinks` reads when the two links ran.
//
// No `browser()`, `nodejs()` or `d8()`: nothing here is run, only linked, and an environment would
// bring a download and a test task with nothing to test. KGP warns about the missing environment and
// says it will be an error one day; that day, `d8()` is the smallest one to pick.
kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        binaries.executable()
    }
}
