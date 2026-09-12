package io.github.youndie.sborka.probe

internal actual fun environmentVariable(name: String): String? = System.getenv(name)

public actual val platformTarget: String
    get() = "jvm (" + System.getProperty("java.runtime.version") + ")"
