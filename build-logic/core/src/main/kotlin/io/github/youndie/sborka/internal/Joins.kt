package io.github.youndie.sborka.internal

import java.io.File

/**
 * `joined-at-neither-end` — a declaration this repository built and never called.
 *
 * **Where it comes from.** shashki found four of these in two days: `PaymentGateway.capture`
 * implemented since B-11 and called by nobody, `SendReceiptUseCase` written and tested and
 * constructed in no DI module (B-37), a sign-in proven at both ends and joined at neither (B-41),
 * kompot renderers with no producer and no consumer (B-32). It is the most expensive class of defect
 * this stack has, and the only one of kapkan's rules that a regular expression cannot find.
 *
 * **Why it is a report and not a gate.** Measured on shashki's own compiled output: 46 of 437 named
 * classes are mentioned by no other file, and none of them is a defect. That is the shape §10 of the
 * research named in advance — a noisy rule moves to `report`, it does not get softened — so this
 * writes a list and never fails a build. What makes the list worth reading is the two piles it comes
 * in: **nothing at all** and **only tests**, and the second is the shape of every one of the four
 * findings above.
 *
 * **What it cannot see, measured rather than supposed:**
 *
 *  * an `inline` function leaves no reference behind — its body is copied into the caller — so the
 *    file holding it can look unused. `suspendRunCatching` in shashki has three call sites in another
 *    file and zero references in the bytecode;
 *  * a `const val` is folded into the call site, so the object declaring it is never named.
 *    `ShashkiTokens` is read from another module and mentioned by nothing;
 *  * a class registered in a Koin module IS referenced — the module's lambda compiles to a class
 *    whose constant pool names the constructor — so "registered and never `get()`-ed" is invisible
 *    here. That is not a choice; it is what the mechanism can say;
 *  * generated code counts as a referrer, which is what keeps a KSP registry's renderers off the
 *    list. The registry itself is still on it, one step up, which is where B-32 was actually visible.
 *
 * **What it deliberately does not report**, because measuring said each of these is noise:
 *
 *  * file facades (`FooKt`) — a facade is not a declaration anybody wrote. 23 of shashki's 46;
 *  * `internal` and `private` declarations — Kotlin's idiom is to declare a helper beside its only
 *    user, and a rule against that is a rule against the language;
 *  * anything whose simple name is declared in a source file this scan cannot find — generated
 *    sources, and anything under a directory that was not handed in.
 */
object Joins {
    /** A top-level class, object or interface, as its source declares it. */
    data class Declaration(
        val qualifiedName: String,
        val file: File,
        val line: Int,
        val isPublic: Boolean,
        val isSuppressed: Boolean,
    )

    data class Finding(
        val qualifiedName: String,
        val file: File,
        val line: Int,
        val testsOnly: Boolean,
    )

    data class Report(
        val findings: List<Finding>,
        val functionFindings: List<Finding>,
        val classesRead: Int,
        val declarationsConsidered: Int,
        val functionsConsidered: Int,
        val suppressed: List<String>,
    )

    /**
     * The id, and the suppression: `@Suppress("kapkan:joined-at-neither-end", "a reason")`.
     *
     * WITHOUT KTLINT'S `ktlint:` PREFIX, and the difference is honest rather than sloppy. ktlint
     * validates the ids inside a `@Suppress` against the rules it loaded and refuses one it does not
     * know — which is what makes a suppression the wiring test for the rest of kapkan. It does not
     * load this rule, because this rule is a Gradle task reading class files, so claiming its prefix
     * would fail every build that used it.
     *
     * The reason beside it is still required and still by ktlint: `kapkan:suppression-needs-a-reason`
     * knows both prefixes.
     */
    const val RULE: String = "kapkan:joined-at-neither-end"

    private val packageOf = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)

    // ANCHORED AT COLUMN ZERO, which is what makes it a top-level declaration: a nested class is
    // indented, and belongs to the class it sits in rather than being one of its own.
    private val declaration =
        Regex(
            """^(public |internal |private )?""" +
                """(?:expect |actual )?""" +
                """(?:abstract |open |sealed |data |value |enum |annotation |fun )*""" +
                """(?:class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)""",
            RegexOption.MULTILINE,
        )

    // A `fun`, and everything in front of its name. The name is the LAST identifier before the
    // parameter list, which is what makes an extension function work: `fun Ride.summary(` declares
    // `summary` on a facade, and taking the first identifier would file it under `Ride`.
    //
    // Indentation is the owner. Column zero is a top-level function and belongs to the file's facade
    // class; four spaces is a member of the top-level declaration above it — and that is not a guess
    // about a repository's habits, it is `ktlint_official`, which `sborka.lint` pins along with the
    // `.editorconfig` it reads. Anything deeper is a nested class, a companion or a local function,
    // and none of those is looked at.
    private val function =
        Regex(
            """^( {0,4})((?:\w+ )*)fun\s+([^(\n]*)\(""",
            RegexOption.MULTILINE,
        )

    private val identifier = Regex("""[A-Za-z_][A-Za-z0-9_]*|`[^`]+`""")

    /**
     * Names whose call site is not a call, or whose absence means nothing.
     *
     * `equals`, `hashCode`, `toString` and `component1` are invoked by the language rather than by a
     * caller anybody wrote. `main` is an entry point. `serializer` is asked for by name from
     * generated code this scan may not be looking at.
     */
    private val notWorthAsking =
        setOf("main", "equals", "hashCode", "toString", "copy", "serializer", "invoke", "iterator")

    /**
     * Modifiers that answer the question before it is asked.
     *
     * `override` — the call names the supertype, so an override is never in anybody's constant pool
     * and every single one of them would be a finding. `inline` — the body is copied into the caller
     * and the reference disappears; `suspendRunCatching` in shashki has three call sites in another
     * file and none in the bytecode. `expect` — there is no bytecode at all. `private`, `protected`
     * and `internal` — see the class rule.
     */
    private val silencing = setOf("override", "inline", "expect", "private", "protected", "internal")

    /**
     * @param classDirs the compiled output of every module, main and test alike — the test half is
     *   what tells "nothing at all" from "only tests", so leaving it out would lose the interesting
     *   pile.
     * @param sourceDirs every module's `src`. Sources are read for three things bytecode cannot say:
     *   whether a declaration is Kotlin-`public` (an `internal` class is `public` in bytecode and
     *   unmangled), which line it is on, and whether somebody has already answered for it with a
     *   `@Suppress`.
     */
    fun scan(
        classDirs: Iterable<File>,
        sourceDirs: Iterable<File>,
    ): Report {
        val declarations = declarationsIn(sourceDirs)
        val functions = functionsIn(sourceDirs, declarations)
        val fileOf = declarations.mapValues { (_, declaration) -> declaration.file }

        var classesRead = 0
        val mentionedBy = HashMap<String, MutableSet<String>>()
        val calledBy = HashMap<String, MutableSet<String>>()
        val compiled = HashMap<String, Boolean>() // qualified name -> is a test class

        // READ FIRST, RESOLVE SECOND. A call is compiled against the type the CALLER holds, so
        // `viewport.toGeo(…)` names `MapViewport.toGeo` even though `toGeo` is declared on the
        // `Projection` it implements — and the hierarchy that settles it is not complete until every
        // class file has been read.
        val parsedClasses = ArrayList<Pair<ClassFile.Parsed, Boolean>>()
        classDirs.filter { it.isDirectory }.forEach { root ->
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .forEach { file ->
                    val parsed = ClassFile.parse(file) ?: return@forEach
                    classesRead++
                    parsedClasses += parsed to isTestOutput(file, root)
                }
        }

        val supertypesOf = parsedClasses.associate { (parsed, _) -> parsed.name to parsed.supertypes }

        fun withSupertypes(owner: String): Set<String> {
            val seen = LinkedHashSet<String>()
            val pending = ArrayDeque(listOf(owner))
            while (pending.isNotEmpty()) {
                val next = pending.removeFirst()
                if (seen.add(next)) pending += supertypesOf[next].orEmpty()
            }
            return seen
        }

        parsedClasses.forEach { (parsed, isTest) ->
            val owner = parsed.name
            val referrer = if (isTest) TEST_PREFIX + owner else owner
            compiled.merge(owner, isTest) { existing, new -> existing && new }
            parsed.mentions.forEach { mentioned ->
                if (mentioned != owner) mentionedBy.getOrPut(mentioned) { HashSet() }.add(referrer)
            }
            parsed.calls.forEach { called ->
                val holder = called.substringBeforeLast('.')
                val member = called.substringAfterLast('.')
                withSupertypes(holder).forEach { type ->
                    calledBy.getOrPut("$type.$member") { HashSet() }.add(referrer)
                }
            }
        }

        val candidates =
            declarations.values.filter { declaration ->
                declaration.isPublic && compiled[declaration.qualifiedName] == false
            }

        val findings =
            candidates.mapNotNull { declaration ->
                if (declaration.isSuppressed) return@mapNotNull null
                val mentions = mentionedBy[declaration.qualifiedName].orEmpty()
                // A NAME MENTIONED BY ITS OWN NESTED CLASSES IS NOT MENTIONED. `Foo$Bar` names `Foo`
                // in its own pool, and so does every lambda Kotlin lifts out of it.
                val elsewhere =
                    mentions.filterNot { mention ->
                        val name = mention.removePrefix(TEST_PREFIX)
                        name.substringBefore('$') == declaration.qualifiedName ||
                            fileOf[name.substringBefore('$')] == declaration.file
                    }
                when {
                    elsewhere.isEmpty() -> {
                        Finding(declaration.qualifiedName, declaration.file, declaration.line, false)
                    }

                    elsewhere.all { it.startsWith(TEST_PREFIX) } -> {
                        Finding(declaration.qualifiedName, declaration.file, declaration.line, true)
                    }

                    else -> {
                        null
                    }
                }
            }

        // A FUNCTION IS ASKED A NARROWER QUESTION THAN A CLASS: not "does anything outside its file
        // name it" but "does anything call it at all", its own class included. The difference is the
        // Screen/Content split — a `Content` composable is called by the `Screen` beside it and by
        // nothing else, thirteen times per application, and every one of those is correct. Nothing is
        // lost: `PaymentGateway.capture` was called by nobody at all, including its own file.
        val callable =
            functions.values.filter { function ->
                function.isPublic && compiled[function.qualifiedName.substringBeforeLast('.')] == false
            }

        val functionFindings =
            callable.mapNotNull { function ->
                if (function.isSuppressed) return@mapNotNull null
                val callers = calledBy[function.qualifiedName].orEmpty()
                when {
                    callers.isEmpty() -> {
                        Finding(function.qualifiedName, function.file, function.line, false)
                    }

                    callers.all { it.startsWith(TEST_PREFIX) } -> {
                        Finding(function.qualifiedName, function.file, function.line, true)
                    }

                    else -> {
                        null
                    }
                }
            }

        return Report(
            findings = findings.sortedBy { it.qualifiedName },
            functionFindings = functionFindings.sortedBy { it.qualifiedName },
            classesRead = classesRead,
            declarationsConsidered = candidates.size,
            functionsConsidered = callable.size,
            suppressed =
                (candidates + callable)
                    .filter { it.isSuppressed }
                    .map { it.qualifiedName }
                    .sorted(),
        )
    }

    /**
     * Every `fun` a caller could name, with the class the JVM files it under.
     *
     * A top-level function belongs to the file's FACADE — `Ride.kt` compiles one called `RideKt` —
     * and a member belongs to the last top-level declaration above it. Neither is guesswork: the
     * facade name is the compiler's rule, and the indentation is `ktlint_official`, which
     * `sborka.lint` pins together with the `.editorconfig` that says so.
     *
     * NOT LOOKED AT, and each of these would be a false positive rather than a gap: `override`, whose
     * call names the supertype; `inline`, whose body is copied into the caller; `expect`, which has no
     * bytecode; and anything the language calls for you.
     */
    private fun functionsIn(
        sourceDirs: Iterable<File>,
        types: Map<String, Declaration>,
    ): Map<String, Declaration> {
        val byFile = types.values.groupBy { it.file }
        val found = HashMap<String, Declaration>()
        sourceDirs.filter { it.isDirectory }.forEach { root ->
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val text = file.readText()
                    val packageName =
                        packageOf
                            .find(text)
                            ?.groupValues
                            ?.get(1)
                            .orEmpty()
                    val facade = qualify(packageName, file.nameWithoutExtension + "Kt")
                    val enclosing = byFile[file].orEmpty().sortedBy { it.line }

                    function.findAll(text).forEach { match ->
                        val modifiers =
                            match.groupValues[2]
                                .trim()
                                .split(" ")
                                .filter { it.isNotBlank() }
                        if (modifiers.any { it in silencing }) return@forEach
                        val name =
                            identifier
                                .findAll(match.groupValues[3])
                                .lastOrNull()
                                ?.value
                                ?.trim('`') ?: return@forEach
                        if (name in notWorthAsking || name.startsWith("component")) return@forEach

                        val line = text.take(match.range.first).count { it == '\n' } + 1
                        val owner =
                            if (match.groupValues[1].isEmpty()) {
                                facade
                            } else {
                                enclosing.lastOrNull { it.line < line }?.qualifiedName ?: return@forEach
                            }
                        found["$owner.$name"] =
                            Declaration(
                                qualifiedName = "$owner.$name",
                                file = file,
                                line = line,
                                isPublic = true,
                                isSuppressed = suppressed(text, match.range.first),
                            )
                    }
                }
        }
        return found
    }

    private fun qualify(
        packageName: String,
        simpleName: String,
    ): String = if (packageName.isEmpty()) simpleName else "$packageName.$simpleName"

    /**
     * QUALIFIED BY THE FILE'S OWN `package`, not by the simple name.
     *
     * Two modules of one repository holding a `Config` each is ordinary, and a scan keyed on simple
     * names would have each of them answer for the other's references — which is not a false positive
     * but a false negative, the kind that is never noticed.
     */
    private fun declarationsIn(sourceDirs: Iterable<File>): Map<String, Declaration> {
        val found = HashMap<String, Declaration>()
        sourceDirs.filter { it.isDirectory }.forEach { root ->
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val text = file.readText()
                    val packageName =
                        packageOf
                            .find(text)
                            ?.groupValues
                            ?.get(1)
                            .orEmpty()
                    declaration.findAll(text).forEach { match ->
                        val visibility = match.groupValues[1].trim()
                        val simpleName = match.groupValues[2]
                        val qualified = if (packageName.isEmpty()) simpleName else "$packageName.$simpleName"
                        val line = text.take(match.range.first).count { it == '\n' } + 1
                        found[qualified] =
                            Declaration(
                                qualifiedName = qualified,
                                file = file,
                                line = line,
                                isPublic = visibility != "internal" && visibility != "private",
                                isSuppressed = suppressed(text, match.range.first),
                            )
                    }
                }
        }
        return found
    }

    /**
     * Whether somebody has already answered for this declaration.
     *
     * `@Suppress` has SOURCE retention and is not in the class file, so a bytecode reader cannot see
     * it — which is why this reads the source it is already reading for the line number. The FORM is
     * `kapkan:…` without ktlint's prefix, and the difference is honest: ktlint validates the ids of
     * rules it loads, and it does not load this one. The reason beside it is still required, and
     * still by ktlint — `kapkan:suppression-needs-a-reason` knows both prefixes.
     */
    private fun suppressed(
        text: String,
        declarationStart: Int,
    ): Boolean =
        text
            .take(declarationStart)
            .lines()
            .asReversed()
            // WALKS UP ONLY THROUGH WHAT CAN BELONG TO THIS DECLARATION — annotations, their argument
            // lines, comments and blanks. Anything else ends the walk, so a `@Suppress` on the
            // previous declaration cannot answer for this one.
            .takeWhile { line ->
                val trimmed = line.trim()
                trimmed.isEmpty() ||
                    trimmed.startsWith("@") ||
                    trimmed.startsWith(")") ||
                    trimmed.startsWith("\"") ||
                    trimmed.startsWith("//") ||
                    trimmed.startsWith("*") ||
                    trimmed.startsWith("/*")
            }.any { it.contains(RULE) }

    /**
     * Whether these classes came out of a test compilation.
     *
     * Read off the OUTPUT directory rather than the source: `build/classes/kotlin/desktop/desktopTest`
     * and `build/classes/java/test` are what the build actually produced, and a source path can be
     * shared by several compilations.
     */
    private fun isTestOutput(
        file: File,
        root: File,
    ): Boolean =
        file
            .relativeTo(root)
            .invariantSeparatorsPath
            .split('/')
            .plus(root.invariantSeparatorsPath.split('/'))
            .any { segment -> segment.equals("test", ignoreCase = true) || segment.endsWith("Test") }

    private const val TEST_PREFIX = "test:"
}
