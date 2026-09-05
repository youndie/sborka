package io.github.youndie.sborka.internal

/**
 * The `.editorconfig` sborka ships, carried as a resource inside the plugin jar.
 *
 * A resource rather than a string constant so the file can be read, diffed and copied as a file —
 * and so the one in sborka's own root, which ktlint reads while building this, is the same bytes a
 * consumer gets rather than a copy that agrees with it today.
 */
object EditorconfigReference {
    private const val RESOURCE = "/io/github/youndie/sborka/editorconfig-reference"

    fun text(): String =
        EditorconfigReference::class.java
            .getResourceAsStream(RESOURCE)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("$RESOURCE is missing from the sborka plugin jar")

    /**
     * A repository's copy, as text rather than as bytes: CRLF and CR become LF.
     *
     * What a style file SAYS is not a property of the checkout that produced it. Git on Windows
     * rewrites text files to CRLF unless a `.gitattributes` forbids it, so the same committed file
     * arrives byte-different on a windows-latest runner — and the check then reports that the
     * repository is formatted by a different formatter, about a file nobody edited. Found on the
     * one build here whose matrix includes Windows.
     *
     * Here rather than in the settings plugin script, because a function declared in a precompiled
     * script plugin is a member of the SCRIPT OBJECT: calling it from a task action captures that
     * object, and the configuration cache refuses to serialise one — "cannot serialize Gradle script
     * object references", which names the mechanism and not the mistake.
     */
    fun normalise(text: String): String = text.replace("\r\n", "\n").replace("\r", "\n")
}
