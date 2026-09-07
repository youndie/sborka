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
 * WHAT IT DOES NOT CLAIM. Crossing a threshold is not a defect and this does not say it is. It says
 * a number, next to the flag that number is measured against, and leaves the reading to a person —
 * the same arrangement as `kapkanJoins`, and for a stronger reason: it has NOT been shown that any
 * method in a report like this is one the JIT actually refused. `FreqInlineSize` limits the inlining
 * of a CALLEE, and the biggest bodies here are `invokeSuspend` — a compilation root, which nothing
 * inlines anyway. Turning this into a gate needs `-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining`
 * under load first, and the findings matched against "too big" / "hot method too big" in that log.
 * Until then a report that fails a build would be a counter with an opinion.
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
    ) {
        /** Every threshold this body is over, largest first. */
        val crossed: List<Threshold> get() = ALL.filter { bytes > it.bytes }

        override fun toString(): String = "$className.$name$descriptor"
    }

    data class Report(
        val classesRead: Int,
        val methodsRead: Int,
        val findings: List<Method>,
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

        classDirs.filter { it.isDirectory }.forEach { root ->
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .forEach { file ->
                    val methods = parse(file) ?: return@forEach
                    classesRead++
                    methodsRead += methods.size
                    findings += methods.filter { it.bytes > REPORT_FROM.bytes }
                }
        }

        return Report(
            classesRead = classesRead,
            methodsRead = methodsRead,
            findings = findings.sortedByDescending { it.bytes },
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
                val bytes = codeLength(input, utf8)
                if (bytes != null) methods += Method(className, name, descriptor, bytes)
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
     * The `code_length` of this member's `Code` attribute, or `null` when it has none.
     *
     * `Code` starts with `max_stack` and `max_locals` — four bytes — and then the length. What
     * follows is the body and everything hung off it (line numbers, the exception table, the stack
     * map), none of which is read: the attribute's own length says where the next attribute begins.
     */
    private fun codeLength(
        input: DataInputStream,
        utf8: Map<Int, String>,
    ): Int? {
        var found: Int? = null
        repeat(input.readUnsignedShort()) {
            val name = utf8[input.readUnsignedShort()]
            val length = input.readInt()
            if (name == "Code" && found == null) {
                input.skipBytes(4) // max_stack, max_locals
                found = input.readInt()
                input.skipBytes(length - 8)
            } else {
                input.skipBytes(length)
            }
        }
        return found
    }
}
