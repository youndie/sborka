package io.github.youndie.sborka.internal

import java.io.File

// The comparison behind the "every declared test was executed" check, in a class of its own.
//
// Lifted from the repository where it was written, and where it found three of them. Not in the
// convention script: a `doLast` that calls a top-level function of a `.gradle.kts` file
// captures the script object, and the configuration cache refuses to serialise one. The build fails
// with "cannot serialize Gradle script object references", which names the mechanism and not the
// mistake.
object DeclaredTests {
    // `@Test` on a line of its own, which is how ktlint_official keeps it — and sborka.lint pins
    // both the tool and the `.editorconfig`, so that is not an assumption about a repository's
    // habits but a property of the formatter it runs.
    private val annotation = Regex("""^\s*@Test\s*$""", RegexOption.MULTILINE)

    private val header = Regex("""name="([^"]+)"\s+tests="(\d+)"""")

    private val count = Regex("""tests="(\d+)"""")

    // A TOP-LEVEL CLASS DECLARATION, which is what a test class is. Anchored to the start of a line
    // so a nested class — indented — belongs to the class it sits in rather than starting a new one.
    private val declarations =
        Regex(
            """^(?:internal |public |private )?(?:abstract |open )?class (\w+)""",
            RegexOption.MULTILINE,
        )

    // `package` as the file declares it, which is what a class's qualified name is made of. Reading it
    // off the directory instead only holds where the tree mirrors the packages, and Kotlin does not ask
    // for that.
    private val packageHeader = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)

    /**
     * What a test task's sources declare: `@Test` counts by simple class name, and the names that could
     * not be given one.
     *
     * [ambiguous] is a simple name declared by more than one file among the sources read. The JUnit
     * report names a multiplatform suite `FooTest[jvm]`, without its package, so such a name cannot be
     * matched against a result — and picking one of the files is what made this check flip with the
     * order a runner's filesystem lists directories in.
     */
    class Declared(
        val counts: Map<String, Int>,
        val ambiguous: Map<String, List<File>>,
    )

    // Only the sources and the classes THIS task compiled.
    //
    // THE SOURCE DIRECTORIES ARE THE COMPILATION'S, not the module's `src`. A multiplatform module has
    // one `src` tree and several test tasks over it, and filtering that tree by the class names the
    // task compiled is not enough: `jvmTest` and `nativeTest` may each hold a `CreateFileSystemTest`,
    // in the same package, with different tests — legal, since no compilation sees both. Read from
    // `src/`, the two files had one name between them and the map kept whichever the walk reached
    // last. One repository went red on a runner whose filesystem listed `nativeTest` after `jvmTest`
    // and green on one that did not, with the same commit on both: 2 tests declared and run, 3
    // demanded from the native file.
    fun declaredIn(
        sourceDirs: Iterable<File>,
        testClassesDirs: Iterable<File>,
        excluded: Set<String> = emptySet(),
    ): Declared {
        // MATCHED AGAINST THE FULLY QUALIFIED NAME, because that is what Gradle's patterns are about.
        // Taking the tail after the last dot instead turns `com.example.stand.*` into `*`, which
        // matches every class in the module and silently switches the whole check off — measured, by
        // watching the module's own `jvmTest` stop reporting anything at all.
        val excludedNames = excluded.map { pattern -> Regex(Regex.escape(pattern).replace("*", "\\E.*\\Q")) }
        val compiled =
            testClassesDirs
                .filter { it.isDirectory }
                .flatMap { dir ->
                    dir
                        .walkTopDown()
                        .filter {
                            it.isFile &&
                                it.name.endsWith(
                                    ".class",
                                )
                        }.map { it.name.removeSuffix(".class") }
                }.toSet()

        // ONE ENTRY PER CLASS, NOT PER FILE.
        //
        // The obvious version counted every `@Test` in a file and filed the total under the file's
        // name. A file holding two test classes then reported all of its annotations against one of
        // them, and the other class's runs were counted against nothing: one repository has a
        // `CrashAssessmentTest.kt` with `CrashMetadataExtractorTest` (3 tests) beside
        // `CrashAssessmentTest` (9), and the check demanded 12 from the class that has 9. Both had
        // run. A guard that fails on correct code is worse than no guard — it gets switched off, and
        // takes the cases it was right about with it.
        val found =
            sourceDirs
                .filter { it.isDirectory }
                .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.name.endsWith("Test.kt") }.toList() }
                // Sorted so that whatever is reported about these files reads the same on every runner.
                .distinctBy { it.absolutePath }
                .sortedBy { it.invariantSeparatorsPath }
                .flatMap { file ->
                    val text = file.readText()
                    val packageName =
                        packageHeader
                            .find(text)
                            ?.groupValues
                            ?.get(1)
                            .orEmpty()

                    declarations
                        .findAll(text)
                        .map { it.groupValues[1] to it.range.first }
                        .toList()
                        .let { found ->
                            found.mapIndexed { index, (name, start) ->
                                val end = found.getOrNull(index + 1)?.second ?: text.length
                                Found(name, packageName, file, annotation.findAll(text.substring(start, end)).count())
                            }
                        }
                }.filter { it.count > 0 && it.name in compiled }
                .filterNot { found -> excludedNames.any { it.matches(found.qualifiedName) } }
                .groupBy { it.name }

        // NEVER ONE FILE QUIETLY STANDING FOR ANOTHER. A name two files declare is set aside and handed
        // back, for the caller to say so, rather than resolved by which of them came last.
        return Declared(
            counts = found.filterValues { it.size == 1 }.mapValues { (_, files) -> files.single().count },
            ambiguous = found.filterValues { it.size > 1 }.mapValues { (_, files) -> files.map { it.file } },
        )
    }

    private class Found(
        val name: String,
        packageName: String,
        val file: File,
        val count: Int,
    ) {
        val qualifiedName = if (packageName.isEmpty()) name else "$packageName.$name"
    }

    // `--tests` ON THE COMMAND LINE, WHICH IS A DIFFERENT FILTER FROM THE BUILD SCRIPT'S.
    //
    // `TestFilter.includePatterns` carries only what a build file set. What `--tests` sets lives on
    // `DefaultTestFilter`, which is Gradle internal — so it is reached reflectively rather than by a
    // cast, and a Gradle version that renames it makes this return null instead of failing to compile
    // in a place nobody is looking.
    //
    // NOT SILENT WHEN IT CANNOT TELL. A run whose filter is unknown is treated as filtered, because
    // the alternative is condemning every class in the module on every `--tests` run — and the caller
    // says so on the console either way. This cost a red default branch: CI's conformance step is
    // `:server:test --tests 'com.example.conformance.*'`, and the first version of this check knew only
    // about the build-script filter.
    fun commandLinePatterns(filter: Any): Set<String> =
        runCatching {
            @Suppress("UNCHECKED_CAST")
            filter.javaClass
                .getMethod("getCommandLineIncludePatterns")
                .invoke(filter) as Set<String>
        }.getOrElse { setOf("<filter could not be read: ${it.javaClass.simpleName}>") }

    fun reportedIn(resultsDir: File): Map<String, Int> {
        if (!resultsDir.isDirectory) return emptyMap()

        return resultsDir
            .listFiles { file -> file.name.endsWith(".xml") }
            .orEmpty()
            .mapNotNull { file ->
                header.find(file.readText(Charsets.UTF_8).take(600))?.let {
                    it.groupValues[1] to
                        it.groupValues[2].toInt()
                }
            }.toMap()
    }

    /**
     * How many test cases a test task actually wrote, whatever kind of task it was.
     *
     * The comparison above needs a JVM `Test` — it wants the classes THIS task compiled, and a
     * Kotlin/Native test binary has none to look at. This does not: it counts, and a count is enough
     * for the one question that can be asked of any suite — did it run anything at all.
     *
     * WALKS THE TREE rather than listing the directory, because a multiplatform test task nests its
     * results a level deeper than `Test` does, and a reader that lists one level finds nothing and
     * reports zero — which, for a check that fails on zero, is the loudest possible wrong answer.
     */
    fun executedIn(resultsDir: File): Int {
        if (!resultsDir.isDirectory) return 0

        return resultsDir
            .walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .sumOf { file ->
                count
                    .find(file.readText(Charsets.UTF_8).take(600))
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
                    ?: 0
            }
    }

    // Reported may legitimately EXCEED declared — a `@TestFactory` produces dynamic cases, and
    // a generated screenshot fixture is one. Only a shortfall is a defect.
    // A multiplatform test task names its suite `MyTest[jvm]`, so the target has to come off before
    // the comparison. Without this the check reports every commonTest class in every multiplatform
    // module as never having run — a guard that cries wolf on a whole module is one that gets deleted.
    private fun simpleNameOf(reportedName: String): String = reportedName.substringAfterLast('.').substringBefore('[')

    fun shortfalls(
        declared: Map<String, Int>,
        reported: Map<String, Int>,
    ): List<String> =
        declared
            .mapNotNull { (simpleName, count) ->
                val ran = reported.entries.firstOrNull { simpleNameOf(it.key) == simpleName }
                when {
                    ran == null -> "$simpleName declares $count @Test and reported nothing at all"
                    ran.value < count -> "${ran.key} declares $count @Test and JUnit ran ${ran.value}"
                    else -> null
                }
            }.sorted()
}
