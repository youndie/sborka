package ru.workinprogress.sborka.internal

import java.io.File

// The comparison behind the "every declared test was executed" check, in a class of its own.
//
// Lifted from konekt, where it was written and where it found three of them. Not in the
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

    // A TOP-LEVEL CLASS DECLARATION, which is what a test class is. Anchored to the start of a line
    // so a nested class — indented — belongs to the class it sits in rather than starting a new one.
    private val declarations =
        Regex(
            """^(?:internal |public |private )?(?:abstract |open )?class (\w+)""",
            RegexOption.MULTILINE,
        )

    // Only the classes THIS task compiled. A multiplatform module has one `src` tree and several test
    // tasks over it, so scanning sources alone would have `jvmTest` demand that an iosTest class
    // appear in its results — a failure with nothing wrong behind it.
    fun declaredIn(
        sourceRoot: File,
        testClassesDirs: Iterable<File>,
        excluded: Set<String> = emptySet(),
    ): Map<String, Int> {
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
        // them, and the other class's runs were counted against nothing: katcher has a
        // `CrashAssessmentTest.kt` with `CrashMetadataExtractorTest` (3 tests) beside
        // `CrashAssessmentTest` (9), and the check demanded 12 from the class that has 9. Both had
        // run. A guard that fails on correct code is worse than no guard — it gets switched off, and
        // takes the cases it was right about with it.
        return sourceRoot
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith("Test.kt") }
            .flatMap { file ->
                // The package from the path: everything under `.../kotlin/`, which is where source
                // sets root their packages.
                val packagePath =
                    file.invariantSeparatorsPath
                        .substringAfterLast("/kotlin/")
                        .substringBeforeLast('/', "")
                        .replace('/', '.')
                val text = file.readText()

                declarations
                    .findAll(text)
                    .map { it.groupValues[1] to it.range.first }
                    .toList()
                    .let { found ->
                        found.mapIndexed { index, (name, start) ->
                            val end = found.getOrNull(index + 1)?.second ?: text.length
                            Triple(name, packagePath, annotation.findAll(text.substring(start, end)).count())
                        }
                    }
            }.filter { (name, _, count) -> count > 0 && name in compiled }
            .filterNot { (name, packagePath, _) ->
                val qualified = if (packagePath.isEmpty()) name else "$packagePath.$name"
                excludedNames.any { it.matches(qualified) }
            }.associate { (name, _, count) -> name to count }
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
    // `:server:test --tests 'io.konekt.conformance.*'`, and the first version of this check knew only
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

    // Reported may legitimately EXCEED declared — a `@TestFactory` produces dynamic cases, and
    // viddik's generated fixture is one. Only a shortfall is a defect.
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
