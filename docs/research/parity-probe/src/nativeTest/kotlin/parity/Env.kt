package parity

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import kotlin.test.Test

/** The native half of the header; see the JVM one for why this is not a compared row. */
class Env {
    @OptIn(ExperimentalNativeApi::class)
    @Test
    fun env() {
        println("PARITYENV\truntime\tKotlin/Native ${Platform.osFamily} ${Platform.cpuArchitecture}")
        println("PARITYENV\thost\t${Platform.osFamily} ${Platform.cpuArchitecture}")
    }
}
