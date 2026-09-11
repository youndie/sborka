package io.github.youndie.sborka.internal

import java.io.DataInputStream
import java.io.File

/**
 * Method bodies measured against the thresholds C2 uses to decide what it will inline.
 *
 * WHY BYTECODE AND NOT SOURCE, which is why this is not a ktlint rule: the thresholds are counted in
 * bytes of a compiled body, and the two things in Kotlin that inflate one are invisible in the file
 * that produced it. A `suspend` function becomes a state machine whose size has little to do with how
 * long it reads; an `inline` function's body is copied into every caller, so the caller grows and the
 * source does not say by how much. On the zavarnik stand `Pricing.quote` reads as a dozen lines and
 * compiles to 1827 bytes.
 *
 * THE THRESHOLD DOES FIRE IN THE HOT PATH — measured, and it corrected what stood here before.
 * zavarnik's bench service under load (31 418 rps on /business in the clean window, 36 692 in the
 * second, JDK 25.0.4, `-XX:+PrintCompilation -XX:+PrintInlining`): of 858 "too big" refusals in the compiler's log, five name `bench` code, and
 * every one of them is "hot method too big" —
 *
 *   bench.Pricing::quote                     1827 bytes   refused twice
 *   bench.MainKt$main$1$2$9::invokeSuspend    816 bytes   refused twice
 *   bench.Order$$serializer::deserialize      374 bytes   refused once
 *
 * So `invokeSuspend` IS offered for inlining rather than only being a compilation root, which is the
 * argument this comment used to make against itself. Three of the ten methods over the threshold
 * were refused; seven never came up.
 *
 * WHAT THAT STILL DOES NOT MAKE IT. A refusal is not a cost: nothing here measured the service with
 * those bodies made smaller, so "the JIT declined to inline this" and "this is slow" are two
 * statements and only the first has evidence. The report therefore names methods and the flags they
 * cross, and leaves the reading to a person — the same arrangement as `kapkanJoins`.
 */
object MethodSizes {
    private const val MAGIC = -0x35014542 // 0xCAFEBABE as a signed Int

    /**
     * A C2 flag and what it decides.
     *
     * The values are this JDK's, read with `-XX:+PrintFlagsFinal -version` on 25.0.4 rather than
     * remembered — they are product flags and they do move between releases. The report prints the
     * number beside the finding so that a reader is never comparing against the wrong one.
     */
    data class Threshold(
        val flag: String,
        val bytes: Int,
        val meaning: String,
    )

    val MAX_INLINE_SIZE = Threshold("MaxInlineSize", 35, "a cold callee longer than this is not inlined")
    val FREQ_INLINE_SIZE = Threshold("FreqInlineSize", 325, "a hot callee longer than this is not inlined")
    val INLINE_SMALL_CODE = Threshold("InlineSmallCode", 2500, "a compiled callee larger than this is not re-inlined")
    val HUGE_METHOD_LIMIT = Threshold("HugeMethodLimit", 8000, "a method longer than this is not compiled at all")

    /**
     * The thresholds a body is measured against, largest first.
     *
     * `MaxInlineSize` is NOT the floor, and that is the one judgement in this file. At 35 bytes it
     * matches almost every method that does anything, and a report that names almost everything is a
     * counter rather than a finding — the same ratio argument that keeps `kapkanJoins` out of
     * `check`. It stays in the table because a reader looking at a 40-byte method in a hot loop wants
     * the number, but the report starts at `FreqInlineSize`.
     */
    val ALL = listOf(HUGE_METHOD_LIMIT, INLINE_SMALL_CODE, FREQ_INLINE_SIZE, MAX_INLINE_SIZE)

    val REPORT_FROM = FREQ_INLINE_SIZE

    /**
     * How many eager materialisations in one body make it a finding.
     *
     * Two, because one is a method that builds a collection and two is a chain that builds one and
     * throws it away. The number is not a guess about taste: on the zavarnik stand the intermediate
     * containers of `filter`/`map` chains were 2.7–4.2 % of every byte allocated, and on konekt —
     * a service written before any of this — the methods that answer "two or more" own 30–32 % of
     * everything user code allocates. `docs/research/research-perf-lint.md` §1.4.
     */
    const val CHAIN_FROM = 2

    /**
     * The containers an inlined operator leaves behind.
     *
     * `filter`, `map` and `groupBy` are `inline`, so a chain of them compiles to no calls at all —
     * each link becomes a fresh container plus a loop. These are the containers the standard
     * library's own implementations allocate.
     */
    private val CONTAINERS =
        setOf(
            "java.util.ArrayList",
            "java.util.LinkedHashMap",
            "java.util.LinkedHashSet",
            "java.util.HashMap",
            "java.util.HashSet",
            "java.util.TreeMap",
        )

    /**
     * Where the operators that are NOT inline live — and `kotlin.text` is on the list on evidence.
     *
     * A definition watching only `kotlin.collections` named none of the methods konekt's allocation
     * profile charges: its largest single user-code owner is `MoneyFormat.group`, whose body was
     * `reversed().chunked(3).joinToString(sep).reversed()` — four intermediates, not one of them a
     * collection. Widening to strings named the top two. The measurement corrected the rule rather
     * than confirming it.
     */
    private val EAGER_OWNERS =
        setOf(
            "kotlin.collections.CollectionsKt",
            "kotlin.collections.ArraysKt",
            "kotlin.collections.MapsKt",
            "kotlin.collections.SetsKt",
            "kotlin.text.StringsKt",
        )

    /**
     * Operators that return a new collection or a new string, by name.
     *
     * The inline ones are absent on purpose — they are not calls (see [CONTAINERS]) — and so is
     * everything lazy: a `Sequence` or a `Flow` chain materialises once at the end, which is the fix
     * this question exists to ask for. In bytecode the two are different call targets, so no type
     * resolution is needed to tell them apart, and that is the reason this is a class-file question
     * rather than a ktlint rule.
     */
    private val EAGER_OPERATORS =
        setOf(
            "sorted",
            "sortedArray",
            "sortedWith",
            "reversed",
            "distinct",
            "flatten",
            "zip",
            "toList",
            "toMutableList",
            "toSet",
            "toMutableSet",
            "toTypedArray",
            "toCharArray",
            "take",
            "takeLast",
            "drop",
            "dropLast",
            "chunked",
            "windowed",
            "plus",
            "minus",
            "joinToString",
            "split",
            "lines",
            "padStart",
            "padEnd",
            "repeat",
            "replace",
            "substringAfter",
            "substringAfterLast",
            "substringBefore",
            "substringBeforeLast",
            "trim",
            "removePrefix",
            "removeSuffix",
        )

    data class Method(
        val className: String,
        val name: String,
        val descriptor: String,
        val bytes: Int,
        /**
         * `Intrinsics.check*` call sites in this body — the null checks Kotlin emits on parameters
         * and on the results of Java calls. `-Xno-param-assertions` and `-Xno-call-assertions`
         * between them remove these; the count is what says whether removing them would remove
         * anything worth the argument.
         */
        val assertions: Int = 0,
        /**
         * `Regex(…)` or `Pattern.compile(…)` inside this body. A pattern is a constant with a
         * compiler attached: built in `<clinit>` it is paid for once, built here it is paid for on
         * every call — which is why `<clinit>` is not counted.
         */
        val patternsCompiled: Int = 0,
        /**
         * Eager containers and strings this body materialises.
         *
         * [CHAIN_FROM] or more of them is a chain that allocates one container per link. THE COUNT
         * IS AN UPPER BOUND and the report says so: a class file carries no dataflow here, so two
         * unrelated lists built in one method count as two. What it is not is a guess — the shape
         * it counts is the one an allocation profile charged, and the same count from a probe over
         * eleven repositories is in the research beside the number of findings it produces.
         */
        val materialisations: Int = 0,
        /**
         * The compilation outputs this body was found in, relative to the scanned directory.
         *
         * More than one when a multiplatform build wrote the same class twice —
         * `kotlin/jvm/main` beside `kotlin/androidDebug` — which is the ordinary case and not a
         * defect. The finding is reported once; the list is what says how many copies stood behind
         * it, so "1058 findings" and "1058 methods" cannot drift apart silently.
         */
        val outputs: List<String> = emptyList(),
        /**
         * Whether the copies disagreed about this body.
         *
         * Two targets can compile the same source differently — an `expect`/`actual`, an `inline`
         * function expanded against a different implementation — and then one copy's finding hides
         * the other's. The largest body wins and this says the choice was made, because a silent
         * pick is the shape of wrong this package exists to avoid. Not covered by a test: producing
         * two genuinely different bodies for one class needs two real compilations.
         */
        val divergent: Boolean = false,
        /**
         * Whether the instruction walk got through this body.
         *
         * `false` means the counts above are not answers, and the report says so by name rather
         * than printing their zeroes.
         */
        val walked: Boolean = true,
    ) {
        /** Every threshold this body is over, largest first. */
        val crossed: List<Threshold> get() = ALL.filter { bytes > it.bytes }

        /**
         * Whether this body runs again and again by construction — a composition or a frame.
         *
         * READ OFF THE SIGNATURE, because that is where the Compose compiler puts it: a
         * `@Composable` function gains a `Composer` parameter, and a body that draws takes a
         * `DrawScope`. Nothing else in a class file says how often a method is called.
         *
         * IT IS A MARKER AND NOT A THRESHOLD. `B-04` read the 70 chain findings of the portfolio's
         * two Compose repositories one by one: 7 of them are in a body with one of these in its
         * signature, and the rest are tests, server code living in the same repository, view-model
         * one-shots and decoders. A finding here is worth reading first; it is not worth a rule of
         * its own, and nothing has measured a Compose client to justify one.
         */
        val repeats: Boolean
            get() =
                descriptor.contains("Landroidx/compose/runtime/Composer;") ||
                    descriptor.contains("Landroidx/compose/ui/graphics/drawscope/DrawScope;")

        override fun toString(): String = "$className.$name$descriptor"
    }

    data class Report(
        val classesRead: Int,
        val methodsRead: Int,
        val findings: List<Method>,
        /** Methods that null-check, most first — where `-Xno-param-assertions` would be felt. */
        val assertions: List<Method> = emptyList(),
        /** Methods that build a pattern on every call, most first. */
        val patternsCompiled: List<Method> = emptyList(),
        /** Methods that materialise [CHAIN_FROM] containers or more, most first. */
        val chains: List<Method> = emptyList(),
        /**
         * Copies of a method beyond the first, collapsed into one finding.
         *
         * [classesRead] and [methodsRead] count distinct classes and methods, so this is what says
         * how much of the tree was read to get them.
         */
        val duplicateCopies: Int = 0,
        /**
         * Bodies the instruction walk refused, by name.
         *
         * A method whose walk did not land exactly on the end of its code array is not counted as
         * "no calls found": the two questions above would then be answered with a silent zero,
         * which is the shape of wrong this package exists to avoid. It is named instead.
         */
        val unwalked: List<String> = emptyList(),
    )

    /**
     * Every method body in the given output directories, with the ones over [REPORT_FROM] kept.
     *
     * Abstract and native methods have no `Code` attribute and so no size; they are counted as read
     * and cannot be findings, which is the honest treatment — a method with no body is not a small
     * method.
     */
    fun scan(classDirs: Iterable<File>): Report {
        // ONE ENTRY PER METHOD, KEYED BY ITS SIGNATURE, because a multiplatform build compiles one
        // class into more than one output directory and a reader that walks directories sees the
        // same method two or three times. The probe that measured this portfolio hit exactly that:
        // one `socketUrl` in shashki arrived twice, and nine pattern findings were eight methods.
        // Insertion-ordered, so a report reads in the order the tree was walked.
        val byMethod = LinkedHashMap<String, Method>()
        val classes = HashSet<String>()
        var copies = 0

        classDirs.filter { it.isDirectory }.forEach { root ->
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .forEach { file ->
                    val methods = parse(file) ?: return@forEach
                    val output = outputOf(root, file, methods.firstOrNull()?.className)
                    methods.forEach { method ->
                        classes += method.className
                        val key = method.toString()
                        val seen = byMethod[key]
                        byMethod[key] =
                            if (seen == null) {
                                method.copy(outputs = listOf(output))
                            } else {
                                copies++
                                // THE LARGEST BODY WINS, and the fact that a choice was made is
                                // carried rather than swallowed: two targets can compile one source
                                // differently, and then the copy that is not reported is a finding
                                // nobody sees.
                                val winner = if (method.bytes > seen.bytes) method else seen
                                winner.copy(
                                    outputs = seen.outputs + output,
                                    divergent =
                                        seen.divergent ||
                                            method.bytes != seen.bytes ||
                                            method.patternsCompiled != seen.patternsCompiled ||
                                            method.materialisations != seen.materialisations,
                                )
                            }
                    }
                }
        }

        val methods = byMethod.values.toList()
        return Report(
            classesRead = classes.size,
            methodsRead = methods.size,
            findings = methods.filter { it.bytes > REPORT_FROM.bytes }.sortedByDescending { it.bytes },
            assertions = methods.filter { it.assertions > 0 }.sortedByDescending { it.assertions },
            patternsCompiled =
                methods
                    .filter { it.patternsCompiled > 0 }
                    .sortedByDescending { it.patternsCompiled },
            chains =
                methods
                    .filter { it.materialisations >= CHAIN_FROM }
                    .sortedByDescending { it.materialisations },
            duplicateCopies = copies,
            unwalked = methods.filter { it.bytes > 0 && it.walked.not() }.map { it.toString() },
        )
    }

    /**
     * Which compilation wrote this file: the path from the scanned directory with the package cut
     * off the end, e.g. `kotlin/jvm/main`.
     *
     * The package comes from the class rather than from the path, because the two can disagree —
     * and when they do, the whole relative path is the honest answer.
     */
    private fun outputOf(
        root: File,
        file: File,
        className: String?,
    ): String {
        val relative = file.relativeTo(root).invariantSeparatorsPath
        val tail = className?.replace('.', '/')?.plus(".class") ?: return relative
        return relative.removeSuffix(tail).trimEnd('/').ifEmpty { "." }
    }

    /**
     * The methods of one class file and the length of each body.
     *
     * The walk is the class file's own layout: the pool, then the header, then fields, then methods.
     * Fields cannot be skipped as a block — each carries its own attributes, whose lengths are the
     * only way to know where the next one starts — so they are walked and discarded.
     */
    fun parse(file: File): List<Method>? =
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != MAGIC) return null
            input.readUnsignedShort() // minor
            input.readUnsignedShort() // major

            val pool = ConstantPool.read(input) ?: return null
            val utf8 = pool.utf8

            input.readUnsignedShort() // access_flags
            val thisClass = input.readUnsignedShort()
            val className =
                pool.classNameIndex[thisClass]
                    ?.let { utf8[it] }
                    ?.replace('/', '.')
                    ?: return null

            input.readUnsignedShort() // super_class
            input.skipBytes(2 * input.readUnsignedShort()) // interfaces

            repeat(input.readUnsignedShort()) { skipMember(input) } // fields

            val methods = ArrayList<Method>()
            repeat(input.readUnsignedShort()) {
                input.readUnsignedShort() // access_flags
                val name = utf8[input.readUnsignedShort()] ?: return null
                val descriptor = utf8[input.readUnsignedShort()] ?: return null
                val code = body(input, utf8)
                if (code != null) {
                    val calls = Bytecode.calls(code)
                    methods +=
                        Method(
                            className = className,
                            name = name,
                            descriptor = descriptor,
                            bytes = code.size,
                            assertions = calls.orEmpty().count { pool.member(it.poolIndex).isAssertion() },
                            // `<clinit>` is where a pattern SHOULD be built, so it is not counted:
                            // the finding is a constant rebuilt per call, not the existence of a Regex.
                            patternsCompiled =
                                if (name == "<clinit>") {
                                    0
                                } else {
                                    calls.orEmpty().count { pool.member(it.poolIndex).isPatternBuild() }
                                },
                            materialisations = calls.orEmpty().count { pool.isMaterialisation(it) },
                            walked = calls != null,
                        )
                }
            }
            methods
        }

    /** A field or a method whose contents are not wanted: read past it by its attribute lengths. */
    private fun skipMember(input: DataInputStream) {
        input.skipBytes(6) // access_flags, name_index, descriptor_index
        repeat(input.readUnsignedShort()) {
            input.skipBytes(2) // attribute_name_index
            input.skipBytes(input.readInt())
        }
    }

    /**
     * The bytes of this member's `Code` attribute, or `null` when it has none.
     *
     * `Code` starts with `max_stack` and `max_locals` — four bytes — and then the length. What
     * follows the body (line numbers, the exception table, the stack map) is skipped by the
     * attribute's own length, which is what says where the next attribute begins.
     *
     * THE BODY IS READ RATHER THAN SKIPPED because the size is only one of the three questions:
     * the other two are about which calls the body makes, and those need the instructions.
     */
    private fun body(
        input: DataInputStream,
        utf8: Map<Int, String>,
    ): ByteArray? {
        var found: ByteArray? = null
        repeat(input.readUnsignedShort()) {
            val name = utf8[input.readUnsignedShort()]
            val length = input.readInt()
            if (name == "Code" && found == null) {
                input.skipBytes(4) // max_stack, max_locals
                val codeLength = input.readInt()
                val code = ByteArray(codeLength)
                input.readFully(code)
                input.skipBytes(length - 8 - codeLength)
                found = code
            } else {
                input.skipBytes(length)
            }
        }
        return found
    }

    /**
     * Whether this call site allocates something a chain would throw away.
     *
     * Two shapes, because a chain has two: `new java/util/ArrayList` is what an INLINED operator
     * leaves in its caller, and a call into one of the [EAGER_OWNERS] facades is the operator that
     * was not inline. `$default` is stripped — an operator called with a default argument arrives
     * as `joinToString$default` and is the same operator.
     */
    private fun ConstantPool.Pool.isMaterialisation(call: Bytecode.Call): Boolean =
        when (call.opcode) {
            Bytecode.NEW -> {
                className(call.poolIndex) in CONTAINERS
            }

            Bytecode.INVOKESTATIC -> {
                val member = member(call.poolIndex)
                val owner = member?.substringBeforeLast('.')
                val name = member?.substringAfterLast('.')?.substringBefore("\$default")
                owner in EAGER_OWNERS && name in EAGER_OPERATORS
            }

            else -> {
                false
            }
        }

    /** The null checks Kotlin emits; the two `-Xno-*-assertions` flags remove them. */
    private fun String?.isAssertion(): Boolean = this != null && startsWith("kotlin.jvm.internal.Intrinsics.check")

    /**
     * Building a pattern: Kotlin's `Regex(…)` and Java's `Pattern.compile(…)`.
     *
     * `Regex` is `new` plus `invokespecial <init>`, so the constructor is what names it — there is no
     * factory to look for.
     */
    private fun String?.isPatternBuild(): Boolean =
        this == "kotlin.text.Regex.<init>" || this == "java.util.regex.Pattern.compile"
}
