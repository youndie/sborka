package ru.workinprogress.sborka.internal

/**
 * The `.editorconfig` sborka ships, carried as a resource inside the plugin jar.
 *
 * A resource rather than a string constant so the file can be read, diffed and copied as a file —
 * and so the one in sborka's own root, which ktlint reads while building this, is the same bytes a
 * consumer gets rather than a copy that agrees with it today.
 */
object EditorconfigReference {
    private const val RESOURCE = "/ru/workinprogress/sborka/editorconfig-reference"

    fun text(): String =
        EditorconfigReference::class.java
            .getResourceAsStream(RESOURCE)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("$RESOURCE is missing from the sborka plugin jar")
}
