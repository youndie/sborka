package io.github.youndie.sborka.internal

import java.io.File
import java.io.StringReader
import java.util.Properties
import java.util.zip.ZipFile

/**
 * Whether a cinterop klib carries what a CONSUMER's link needs (sborka#116).
 *
 * A library with a binding can be green everywhere inside its own build and unlinkable everywhere
 * outside it. It happened twice in the portfolio the same way: the archives and `-l` options sat on
 * the library's own binaries (`binaries.all { linkerOpts(…) }`), so its tests linked, `check` passed
 * and the publish succeeded, while the published `…-cinterop-<name>.klib` carried the bindings and
 * nothing they bind to. The first build outside the repository failed with `undefined symbol` on every
 * C function (kafkakn B-15, smtpkn M-110).
 *
 * Two halves, both here as string and file reading so that they can be tested without a toolchain:
 *
 * - [namesALibrary] decides which options on a module's own binaries are refused. Only the ones that
 *   name a library: `-Wl,--as-needed`, which `sborka.kmp` adds itself, has nothing to do with a
 *   binding and stays allowed.
 * - [read] and [verdict] read the klib itself, because the build file is the wrong witness: the
 *   `.def` decides what the klib carries, and `extraOpts("-staticLibrary", …)` carries an archive
 *   without writing any manifest key for it. So the archives are read from `included/`, not from the
 *   manifest.
 */
object CinteropLink {
    /** The comment in a `.def` that says its headers need nothing linked, and why. */
    const val LINKS_NOTHING_MARKER: String = "sborka: links nothing"

    private val sharedObject = Regex(""".*\.so(\.[0-9]+)*$""")

    /**
     * The options among [options] that name a library: `-l<x>`, `-L<dir>`, `-framework <x>`, or a
     * path to an archive or a shared object — on their own or inside a `-Wl,` list. A bare `-l`, `-L`
     * or `-framework` takes the next option with it.
     */
    fun librariesNamed(options: List<String>): List<String> {
        val named = mutableListOf<String>()
        var index = 0
        while (index < options.size) {
            val option = options[index]
            if (option in setOf("-l", "-L", "-framework") && index + 1 < options.size) {
                named += "$option ${options[index + 1]}"
                index += 2
                continue
            }
            if (namesALibrary(option)) named += option
            index++
        }
        return named
    }

    /** Whether one option, as written, names a library. */
    fun namesALibrary(option: String): Boolean {
        if (option.startsWith("-Wl,")) {
            return option.removePrefix("-Wl,").split(',').any { namesALibrary(it) }
        }
        return (option.startsWith("-l") && option.length > 2) ||
            (option.startsWith("-L") && option.length > 2) ||
            option in setOf("-l", "-L", "-framework") ||
            option.endsWith(".a") ||
            option.endsWith(".dylib") ||
            sharedObject.matches(option)
    }

    /**
     * Whether a `.def` declares headers for one target: `headers`, `headers.<family>` (`linux`, `osx`)
     * or `headers.<target>` (`linux_x64`). Only the properties part counts — below `---` is C, where
     * a `headers =` line would be text, not a key.
     */
    fun declaresHeaders(
        definition: String,
        target: String,
        family: String,
    ): Boolean {
        val properties = properties(definition)
        return listOf("headers", "headers.$family", "headers.$target").any {
            !properties.getProperty(it).isNullOrBlank()
        }
    }

    /** The reason a `.def` gives for linking nothing, or null when it has no such comment. */
    fun linksNothingBecause(definition: String): String? =
        propertiesPart(definition)
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("#") }
            .map { it.removePrefix("#").trim() }
            .firstOrNull { it.startsWith(LINKS_NOTHING_MARKER) }
            ?.removePrefix(LINKS_NOTHING_MARKER)
            ?.trim()
            ?.trimStart(':', '-', '—')
            ?.trim()

    /** What one cinterop klib carries for a consumer's link. */
    data class Carried(
        /** File names under `default/targets/<target>/included/`, with their sizes in bytes. */
        val archives: Map<String, Long>,
        /** The manifest's `linkerOpts`, split on whitespace. */
        val linkerOpts: List<String>,
        /** The manifest's `staticLibraries`, which `-staticLibrary` does not write. */
        val staticLibraries: List<String>,
    ) {
        /** An archive to link, or an option in the manifest that names a library. */
        val carriesALink: Boolean
            get() = archives.isNotEmpty() || librariesNamed(linkerOpts).isNotEmpty()
    }

    /**
     * Reads a klib, packed (a `.klib` zip) or unpacked (a directory with the same layout), for one
     * target. The manifest keeps a `.def`'s per-platform keys as they were written —
     * `linkerOpts.linux=-lrt` in razves's — and they are resolved at the consumer's link, so the
     * options for [target] are the plain key plus the one for its [family] and the one for itself.
     */
    fun read(
        klib: File,
        target: String,
        family: String,
    ): Carried {
        val entries: Map<String, Long>
        val manifest: String?
        if (klib.isDirectory) {
            entries =
                klib
                    .walkTopDown()
                    .filter { it.isFile }
                    .associate { it.relativeTo(klib).invariantSeparatorsPath to it.length() }
            manifest = entries.keys.firstOrNull(::isManifest)?.let { File(klib, it).readText() }
        } else {
            ZipFile(klib).use { zip ->
                val all = zip.entries().toList().filterNot { it.isDirectory }
                entries = all.associate { it.name to it.size }
                manifest =
                    all.firstOrNull { isManifest(it.name) }?.let { entry ->
                        zip.getInputStream(entry).use { String(it.readBytes()) }
                    }
            }
        }
        val properties = Properties().apply { manifest?.let { load(StringReader(it)) } }
        return Carried(
            archives =
                entries
                    .filterKeys { included.containsMatchIn(it) }
                    .mapKeys { (path, _) -> path.substringAfterLast('/') }
                    .toSortedMap(),
            linkerOpts = forTarget(properties, "linkerOpts", target, family),
            staticLibraries = forTarget(properties, "staticLibraries", target, family),
        )
    }

    /**
     * Why a klib is refused, or null when it is not.
     *
     * Refused: a `.def` that declares headers and a klib that carries neither an archive nor any
     * library in its `linkerOpts` — the B-15 / M-110 signature. A shim with C after `---` and no
     * `headers` has nothing to carry and passes. So does a `.def` with the [LINKS_NOTHING_MARKER]
     * comment and a reason, for headers whose symbols really need nothing linked; but a marker on a
     * klib that does carry something is refused too, because it is no longer true.
     */
    fun verdict(
        declaresHeaders: Boolean,
        linksNothingBecause: String?,
        carried: Carried,
    ): String? =
        when {
            linksNothingBecause != null && linksNothingBecause.isEmpty() -> {
                "the .def says '# $LINKS_NOTHING_MARKER' without a reason. Say why nothing needs linking, " +
                    "after the marker: '# $LINKS_NOTHING_MARKER: <why>'."
            }

            linksNothingBecause != null && carried.carriesALink -> {
                "the .def says '# $LINKS_NOTHING_MARKER', and the klib carries ${describe(carried)}. " +
                    "One of the two is wrong; if the klib is right, delete the marker."
            }

            linksNothingBecause != null || !declaresHeaders || carried.carriesALink -> {
                null
            }

            else -> {
                "the .def declares headers, and the klib carries neither an archive under included/ nor " +
                    "any library in its manifest linkerOpts. This module's own binaries may link; a " +
                    "consumer's link fails with `undefined symbol` on every C function the binding calls. " +
                    "Write what a consumer links INTO THE .def: archives as `staticLibraries = libfoo.a` " +
                    "(their directory from Gradle as `extraOpts(\"-libraryPath\", dir)`), a shared library as " +
                    "`linkerOpts = -L<dir> -lfoo`. If these headers really need nothing linked, say so in the " +
                    ".def: '# $LINKS_NOTHING_MARKER: <why>'."
            }
        }

    /** One line for the report: what the klib carries, archives first. */
    fun describe(carried: Carried): String {
        val parts = mutableListOf<String>()
        if (carried.archives.isNotEmpty()) {
            parts +=
                "included/ " + carried.archives.entries.joinToString(", ") { (name, size) -> "$name ($size bytes)" }
        }
        if (carried.linkerOpts.isNotEmpty()) parts += "linkerOpts ${carried.linkerOpts.joinToString(" ")}"
        if (carried.staticLibraries.isNotEmpty()) {
            parts +=
                "staticLibraries ${carried.staticLibraries.joinToString(" ")}"
        }
        return parts.joinToString("; ").ifEmpty { "nothing" }
    }

    private val included = Regex("""(^|/)targets/[^/]+/included/[^/]+$""")

    private fun isManifest(path: String): Boolean =
        path == "manifest" || (path.endsWith("/manifest") && path.count { it == '/' } == 1)

    private fun forTarget(
        properties: Properties,
        key: String,
        target: String,
        family: String,
    ): List<String> = listOf(key, "$key.$family", "$key.$target").flatMap { split(properties.getProperty(it)) }

    private fun split(value: String?): List<String> =
        value
            ?.trim()
            ?.split(Regex("""\s+"""))
            ?.filter {
                it.isNotEmpty()
            }.orEmpty()

    private fun propertiesPart(definition: String): String =
        definition
            .lineSequence()
            .takeWhile { it.trim() != "---" }
            .joinToString("\n")

    private fun properties(definition: String): Properties =
        Properties().apply {
            load(StringReader(propertiesPart(definition)))
        }
}
