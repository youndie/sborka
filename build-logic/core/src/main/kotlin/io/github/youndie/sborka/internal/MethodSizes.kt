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
         * Whether the instruction walk got through this body.
         *
         * `false` means the two counts above are not answers, and the report says so by name rather
         * than printing their zeroes.
         */
        val walked: Boolean = true,
    ) {
        /** Every threshold this body is over, largest first. */
        val crossed: List<Threshold> get() = ALL.filter { bytes > it.bytes }

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
        var classesRead = 0
        var methodsRead = 0
        val findings = ArrayList<Method>()

        val assertions = ArrayList<Method>()
        val patterns = ArrayList<Method>()
        val unwalked = ArrayList<String>()

        classDirs.filter { it.isDirectory }.forEach { root ->
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .forEach { file ->
                    val methods = parse(file) ?: return@forEach
                    classesRead++
                    methodsRead += methods.size
                    findings += methods.filter { it.bytes > REPORT_FROM.bytes }
                    assertions += methods.filter { it.assertions > 0 }
                    patterns += methods.filter { it.patternsCompiled > 0 }
                    unwalked += methods.filter { it.bytes > 0 && it.walked.not() }.map { it.toString() }
                }
        }

        return Report(
            classesRead = classesRead,
            methodsRead = methodsRead,
            findings = findings.sortedByDescending { it.bytes },
            assertions = assertions.sortedByDescending { it.assertions },
            patternsCompiled = patterns.sortedByDescending { it.patternsCompiled },
            unwalked = unwalked,
        )
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
