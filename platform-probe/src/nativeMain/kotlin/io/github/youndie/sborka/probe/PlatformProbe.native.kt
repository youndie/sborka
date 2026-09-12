package io.github.youndie.sborka.probe

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalForeignApi::class)
internal actual fun environmentVariable(name: String): String? = platform.posix.getenv(name)?.toKString()

@OptIn(ExperimentalNativeApi::class)
public actual val platformTarget: String
    get() = "native " + kotlin.native.Platform.osFamily + " " + kotlin.native.Platform.cpuArchitecture
