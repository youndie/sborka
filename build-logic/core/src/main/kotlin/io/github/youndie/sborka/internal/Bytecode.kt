package io.github.youndie.sborka.internal

/**
 * Walking a method body one instruction at a time.
 *
 * WHY THIS EXISTS AT ALL, when the size reader needed no such thing: two of the three questions this
 * package answers are about WHICH CALLS a body makes and where — the parameter assertions Kotlin
 * emits, and a `Regex` built inside a method instead of once in `<clinit>`. Neither can be answered
 * by attribute lengths, and neither can be answered by searching the code array for a byte pattern:
 * an operand can hold any byte, so the only way to know that a `0xB8` is an `invokestatic` and not
 * the second half of an `ldc2_w` is to arrive at it by stepping over everything before it.
 *
 * THE TABLE IS THE JVM SPEC'S, and the three instructions that are not in it are the reason a
 * hand-written walker is worth checking rather than trusting: `wide` changes the width of the
 * instruction after it, and `tableswitch` and `lookupswitch` are padded to a four-byte boundary
 * measured from the START of the method, so their length depends on where they sit. `BytecodeTest`
 * checks the walk against `javap` on compiled fixtures, which is the second reader that would catch
 * a table typo.
 */
internal object Bytecode {
    private const val WIDE = 196
    private const val TABLESWITCH = 170
    private const val LOOKUPSWITCH = 171

    const val INVOKEVIRTUAL = 182
    const val INVOKESPECIAL = 183
    const val INVOKESTATIC = 184
    const val INVOKEINTERFACE = 185
    const val INVOKEDYNAMIC = 186

    /** Total width of each fixed-length instruction, opcode included; 0 marks the variable ones. */
    private val WIDTHS =
        IntArray(256).also { w ->
            w.fill(1)
            listOf(16, 18, 169, 188).forEach { w[it] = 2 } // bipush, ldc, ret, newarray
            (21..25).forEach { w[it] = 2 } // iload…aload
            (54..58).forEach { w[it] = 2 } // istore…astore
            listOf(17, 19, 20, 132, 187, 189, 192, 193, 198, 199).forEach { w[it] = 3 }
            (153..168).forEach { w[it] = 3 } // the branches, goto and jsr
            (178..184).forEach { w[it] = 3 } // the field accesses and the three simple invokes
            w[197] = 4 // multianewarray
            listOf(185, 186, 200, 201).forEach { w[it] = 5 } // invokeinterface, invokedynamic, *_w
            listOf(TABLESWITCH, LOOKUPSWITCH, WIDE).forEach { w[it] = 0 }
        }

    /** One call site: the opcode that made it and the constant-pool index of what it names. */
    data class Call(
        val opcode: Int,
        val poolIndex: Int,
    )

    /**
     * Every method or field reference the body invokes, in order.
     *
     * Returns `null` when the walk does not land exactly on the end of the code array — which is how
     * a wrong width shows up. A walker that guessed and carried on would report calls that are not
     * there, and the count would look like data.
     */
    fun calls(code: ByteArray): List<Call>? {
        val calls = ArrayList<Call>()
        var offset = 0
        while (offset < code.size) {
            val opcode = code[offset].toInt() and 0xFF
            when (opcode) {
                INVOKEVIRTUAL, INVOKESPECIAL, INVOKESTATIC, INVOKEINTERFACE, INVOKEDYNAMIC -> {
                    calls += Call(opcode, u2(code, offset + 1))
                }
            }
            val width = widthAt(code, offset, opcode) ?: return null
            if (width <= 0) return null
            offset += width
        }
        return if (offset == code.size) calls else null
    }

    private fun widthAt(
        code: ByteArray,
        offset: Int,
        opcode: Int,
    ): Int? =
        when (opcode) {
            // `wide` widens the operand of the instruction it prefixes: four bytes for a load or a
            // store, six for `iinc`, which carries two operands.
            WIDE -> {
                val widened = code.getOrNull(offset + 1)?.toInt()?.and(0xFF) ?: return null
                if (widened == 132) 6 else 4
            }

            // PADDED TO A FOUR-BYTE BOUNDARY, and the boundary is measured from the start of the
            // method — so the same instruction has a different length depending on where it sits.
            TABLESWITCH -> {
                val base = offset + 1 + padding(offset)
                val low = u4(code, base + 4)
                val high = u4(code, base + 8)
                val entries = high - low + 1
                if (entries < 0) null else base + 12 + 4 * entries - offset
            }

            LOOKUPSWITCH -> {
                val base = offset + 1 + padding(offset)
                val pairs = u4(code, base + 4)
                if (pairs < 0) null else base + 8 + 8 * pairs - offset
            }

            else -> {
                WIDTHS[opcode]
            }
        }

    private fun padding(offset: Int): Int = (4 - (offset + 1) % 4) % 4

    private fun u2(
        code: ByteArray,
        at: Int,
    ): Int = ((code[at].toInt() and 0xFF) shl 8) or (code[at + 1].toInt() and 0xFF)

    private fun u4(
        code: ByteArray,
        at: Int,
    ): Int =
        ((code[at].toInt() and 0xFF) shl 24) or
            ((code[at + 1].toInt() and 0xFF) shl 16) or
            ((code[at + 2].toInt() and 0xFF) shl 8) or
            (code[at + 3].toInt() and 0xFF)
}
