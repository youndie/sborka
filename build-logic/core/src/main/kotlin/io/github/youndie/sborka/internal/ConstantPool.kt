package io.github.youndie.sborka.internal

import java.io.DataInputStream

/**
 * The constant pool, read once and shared by the two readers that need it.
 *
 * IT IS HERE BECAUSE IT WAS ABOUT TO EXIST TWICE. `ClassFile` reads the pool to answer "what does
 * this class mention and call"; `MethodSizes` reads it to put a name on a method. Neither needs the
 * other's fields, but both need the same walk — and the walk is the part with the trap in it, so a
 * second copy would be a second place to get the trap right.
 *
 * THE TRAP: a `long` or a `double` TAKES TWO POOL SLOTS. The spec calls it a historical mistake; a
 * reader that forgets it walks off the end of the pool and reads rubbish as tags, which fails as a
 * parse error somewhere far from the file that caused it.
 */
internal object ConstantPool {
    const val UTF8 = 1
    const val INTEGER = 3
    const val FLOAT = 4
    const val LONG = 5
    const val DOUBLE = 6
    const val CLASS = 7
    const val STRING = 8
    const val FIELD_REF = 9
    const val METHOD_REF = 10
    const val INTERFACE_METHOD_REF = 11
    const val NAME_AND_TYPE = 12
    const val METHOD_HANDLE = 15
    const val METHOD_TYPE = 16
    const val DYNAMIC = 17
    const val INVOKE_DYNAMIC = 18
    const val MODULE = 19
    const val PACKAGE = 20

    /**
     * As much of the pool as either reader asks for.
     *
     * `methodRefs` is `(class index, name-and-type index)` in pool order, which is what a caller
     * needs to name the owner and the member of a call without resolving anything.
     */
    data class Pool(
        val utf8: Map<Int, String>,
        val classNameIndex: Map<Int, Int>,
        val nameOfNameAndType: Map<Int, Int>,
        val methodRefs: List<Pair<Int, Int>>,
    )

    /**
     * Reads the pool, leaving the stream positioned on `access_flags`.
     *
     * Returns `null` on a tag this does not know — a class file from a version newer than this
     * reader. Refusing to guess is the point: the alternative is a misread that looks like data.
     */
    fun read(input: DataInputStream): Pool? {
        val poolCount = input.readUnsignedShort()
        val utf8 = HashMap<Int, String>()
        val classNameIndex = HashMap<Int, Int>()
        val nameOfNameAndType = HashMap<Int, Int>()
        val methodRefs = ArrayList<Pair<Int, Int>>()

        var index = 1
        while (index < poolCount) {
            when (input.readUnsignedByte()) {
                UTF8 -> {
                    utf8[index] = input.readUTF()
                }

                CLASS -> {
                    classNameIndex[index] = input.readUnsignedShort()
                }

                STRING, METHOD_TYPE, MODULE, PACKAGE -> {
                    input.skipBytes(2)
                }

                METHOD_REF, INTERFACE_METHOD_REF -> {
                    methodRefs += input.readUnsignedShort() to input.readUnsignedShort()
                }

                NAME_AND_TYPE -> {
                    nameOfNameAndType[index] = input.readUnsignedShort()
                    input.skipBytes(2) // the descriptor, which neither reader needs
                }

                INTEGER, FLOAT, FIELD_REF, DYNAMIC, INVOKE_DYNAMIC -> {
                    input.skipBytes(4)
                }

                METHOD_HANDLE -> {
                    input.skipBytes(3)
                }

                // Two slots. See the note above; this `index++` is the whole of that fix.
                LONG, DOUBLE -> {
                    input.skipBytes(8)
                    index++
                }

                else -> {
                    return null
                }
            }
            index++
        }
        return Pool(utf8, classNameIndex, nameOfNameAndType, methodRefs)
    }
}
