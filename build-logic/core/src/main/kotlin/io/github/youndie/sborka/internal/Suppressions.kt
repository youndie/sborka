package io.github.youndie.sborka.internal

import java.io.File

/**
 * Who has already answered for a finding a class file cannot see the answer to.
 *
 * `@Suppress` has SOURCE retention, so a reader of compiled output never sees one. `Joins` solved
 * this by reading the source it was already reading for line numbers; this does the same for the
 * pattern question, and anchors on THE LINE THAT BUILDS THE PATTERN rather than on the declaration
 * around it.
 *
 * WHY THE LINE AND NOT THE DECLARATION. Finding the enclosing declaration of an expression without a
 * parser is guesswork — a `Regex(…)` five lines into a function body has no anchor a regular
 * expression can be sure of. The line that builds it always has one, and it is also where a reader
 * wants the reason: the sentence explaining why this pattern is rebuilt belongs beside the pattern.
 *
 * PUBLIC, like `MethodSizes` and for the same reason — the settings plugin is a different module
 * and `internal` does not cross one. Its signature names only `Sites`, `String` and `File`: a
 * `Pair` or a `Triple` here would hand a consumer a type it cannot name, which is what proba caught
 * on `ConstantPool` and what the comment there is about.
 *
 * The form is `kapkan:` without ktlint's prefix, for the reason [Joins] gives: ktlint validates the
 * ids of the rules it loaded and this is not one of them. The reason beside it is still required and
 * still by ktlint — `kapkan:suppression-needs-a-reason` knows both prefixes.
 */
object Suppressions {
    const val PATTERN_RULE: String = "kapkan:pattern-built-per-call"

    /** A `Regex(…)` or a `Pattern.compile(…)` written in source — the same two the reader counts. */
    private val PATTERN_SITE = Regex("""\bRegex\s*\(|\bPattern\s*\.\s*compile\s*\(""")

    private val PACKAGE = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)

    /** How many of a class's pattern sites carry a suppression, and how many there are at all. */
    data class Sites(
        val total: Int,
        val suppressed: Int,
    )

    /**
     * The pattern sites of the source file that declares [className], with the suppressed ones
     * counted.
     *
     * `null` when no source file can be attributed to the class — a generated class, a class from a
     * dependency, a file this reader could not match. The caller must not read that as "suppressed":
     * a finding whose source cannot be found is a finding nobody has answered for.
     */
    fun sitesOf(
        sourceDirs: Iterable<File>,
        className: String,
    ): Sites? {
        val packageName = className.substringBeforeLast('.', "")
        // The outermost class: a finding in `Foo$Bar` is written in the file that declares `Foo`,
        // and a file facade `FooKt` is written in `Foo.kt`.
        val outer = className.substringAfterLast('.').substringBefore('$')
        val simpleName = outer.removeSuffix("Kt")

        val file =
            sourceDirs
                .asSequence()
                .filter { it.isDirectory }
                .flatMap { it.walkTopDown() }
                .filter { it.isFile && it.extension == "kt" }
                .filter {
                    PACKAGE
                        .find(it.readText())
                        ?.groupValues
                        ?.get(1)
                        .orEmpty() == packageName
                }.firstOrNull { candidate ->
                    candidate.nameWithoutExtension == simpleName ||
                        declaresTypeNamed(candidate.readText(), outer)
                } ?: return null

        val lines = file.readLines()
        val sites = lines.indices.filter { PATTERN_SITE.containsMatchIn(lines[it]) }
        return Sites(total = sites.size, suppressed = sites.count { suppressedAt(lines, it) })
    }

    private fun declaresTypeNamed(
        text: String,
        name: String,
    ): Boolean = Regex("""\b(class|object|interface)\s+$name\b""").containsMatchIn(text)

    /**
     * Whether a suppression stands above this line.
     *
     * WALKS UP ONLY THROUGH WHAT CAN BELONG TO IT — annotations, their argument lines, comments and
     * blanks — so a suppression written above the previous statement cannot answer for this one.
     * The same walk `Joins` uses, against the same misreading.
     */
    private fun suppressedAt(
        lines: List<String>,
        index: Int,
    ): Boolean {
        if (lines[index].contains(PATTERN_RULE)) return true
        return lines
            .take(index)
            .asReversed()
            .takeWhile { line ->
                val trimmed = line.trim()
                trimmed.isEmpty() ||
                    trimmed.startsWith("@") ||
                    trimmed.startsWith(")") ||
                    trimmed.startsWith("\"") ||
                    trimmed.startsWith("//") ||
                    trimmed.startsWith("*") ||
                    trimmed.startsWith("/*")
            }.any { it.contains(PATTERN_RULE) }
    }
}
